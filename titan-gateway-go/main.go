package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"gopkg.in/yaml.v3"
)

// =============================================================================
// Config
// =============================================================================

type Config struct {
	Upstreams struct {
		CoreBanking       string `yaml:"core_banking"`
		Notification      string `yaml:"notification"`
		Promotion         string `yaml:"promotion"`
		Loans             string `yaml:"loans"`
		AIService         string `yaml:"ai_service"`
		FederatedLearning string `yaml:"federated_learning"`
		QKDService        string `yaml:"qkd_service"`
	} `yaml:"upstreams"`
	Auth struct {
		JWTSecret string `yaml:"jwt_secret"`
	} `yaml:"auth"`
	RateLimit struct {
		MaxRequests   int `yaml:"max_requests"`
		WindowSeconds int `yaml:"window_seconds"`
		BlockMinutes  int `yaml:"block_minutes"`
	} `yaml:"rate_limit"`
	Server struct {
		Port string `yaml:"port"`
	} `yaml:"server"`
}

func loadConfig() *Config {
	path := "config.yaml"
	if p := os.Getenv("CONFIG_PATH"); p != "" {
		path = p
	}
	data, err := os.ReadFile(path)
	if err != nil {
		log.Fatalf("failed to read config: %v", err)
	}
	// Expand ${ENV_VAR:default} placeholders in the YAML before parsing so that
	// secrets like jwt_secret can be injected at runtime without hardcoding them.
	expanded := os.ExpandEnv(string(data))
	var cfg Config
	if err := yaml.Unmarshal([]byte(expanded), &cfg); err != nil {
		log.Fatalf("failed to parse config: %v", err)
	}
	// Defaults
	if cfg.Server.Port == "" {
		cfg.Server.Port = "8088"
	}
	if cfg.RateLimit.MaxRequests == 0 {
		cfg.RateLimit.MaxRequests = 100
	}
	if cfg.RateLimit.WindowSeconds == 0 {
		cfg.RateLimit.WindowSeconds = 60
	}
	if cfg.RateLimit.BlockMinutes == 0 {
		cfg.RateLimit.BlockMinutes = 5
	}
	if cfg.Auth.JWTSecret == "" {
		cfg.Auth.JWTSecret = os.Getenv("JWT_SECRET")
	}
	if cfg.Auth.JWTSecret == "" {
		log.Fatalf("JWT secret is not configured — set JWT_SECRET environment variable")
	}
	return &cfg
}

// =============================================================================
// Sliding-Window Rate Limiter + IP Blocker
//
// Each IP keeps a list of request timestamps inside the window.
// On every request:
//  1. Check if IP is blocked → reject immediately.
//  2. Drop timestamps older than the window.
//  3. Count remaining timestamps.
//  4. If count >= maxRequests → BLOCK the IP, reject.
//  5. Otherwise → record timestamp, allow.
//
// A background goroutine removes idle entries every 5 minutes.
// =============================================================================

type ipState struct {
	timestamps   []time.Time
	blockedUntil time.Time
}

type slidingWindowLimiter struct {
	mu           sync.Mutex
	states       map[string]*ipState
	maxRequests  int
	window       time.Duration
	blockDur     time.Duration
	cleanupEvery time.Duration
}

func newSlidingWindowLimiter(maxRequests, windowSeconds, blockMinutes int) *slidingWindowLimiter {
	l := &slidingWindowLimiter{
		states:       make(map[string]*ipState),
		maxRequests:  maxRequests,
		window:       time.Duration(windowSeconds) * time.Second,
		blockDur:     time.Duration(blockMinutes) * time.Minute,
		cleanupEvery: 5 * time.Minute,
	}
	go l.cleanupLoop()
	return l
}

// allow returns (allowed bool, blockedUntil time.Time, currentCount int)
func (l *slidingWindowLimiter) allow(ip string) (bool, time.Time, int) {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	state, ok := l.states[ip]
	if !ok {
		state = &ipState{}
		l.states[ip] = state
	}

	// 1. Already blocked?
	if now.Before(state.blockedUntil) {
		return false, state.blockedUntil, l.maxRequests
	}

	// 2. Slide the window
	cutoff := now.Add(-l.window)
	fresh := state.timestamps[:0]
	for _, t := range state.timestamps {
		if t.After(cutoff) {
			fresh = append(fresh, t)
		}
	}
	state.timestamps = fresh
	count := len(state.timestamps)

	// 3. Limit reached → BLOCK
	if count >= l.maxRequests {
		state.blockedUntil = now.Add(l.blockDur)
		state.timestamps = nil
		log.Printf("🚫 BLOCKED  IP=%-20s  hit %d req/%ds  blocked for %s",
			ip, l.maxRequests, int(l.window.Seconds()), l.blockDur)
		return false, state.blockedUntil, count
	}

	// 4. Allow
	state.timestamps = append(state.timestamps, now)
	return true, time.Time{}, count + 1
}

func (l *slidingWindowLimiter) blockedIPs() map[string]string {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	result := make(map[string]string)
	for ip, state := range l.states {
		if now.Before(state.blockedUntil) {
			result[ip] = fmt.Sprintf("blocked until %s (%.0fs remaining)",
				state.blockedUntil.Format(time.RFC3339),
				time.Until(state.blockedUntil).Seconds())
		}
	}
	return result
}

func (l *slidingWindowLimiter) cleanupLoop() {
	ticker := time.NewTicker(l.cleanupEvery)
	defer ticker.Stop()
	for range ticker.C {
		l.mu.Lock()
		now := time.Now()
		cutoff := now.Add(-l.window)
		removed := 0
		for ip, state := range l.states {
			if now.After(state.blockedUntil) {
				fresh := state.timestamps[:0]
				for _, t := range state.timestamps {
					if t.After(cutoff) {
						fresh = append(fresh, t)
					}
				}
				if len(fresh) == 0 {
					delete(l.states, ip)
					removed++
				} else {
					state.timestamps = fresh
				}
			}
		}
		if removed > 0 {
			log.Printf("🧹 rate-limiter cleanup: removed %d idle IP entries", removed)
		}
		l.mu.Unlock()
	}
}

// =============================================================================
// Public paths — skip JWT validation
// =============================================================================

var publicPaths = []string{
	// Auth
	"/api/v1/auth/login",
	"/api/v1/auth/register",
	// OTP (used before login is complete)
	"/api/auth/otp/",
	// Gateway management (no auth needed for observability)
	"/health",
	"/routes",
	// Notification webhooks (called by external systems, not users)
	"/api/webhooks/",
	"/webhooks/",
}

func isPublic(path string) bool {
	for _, p := range publicPaths {
		if strings.HasPrefix(path, p) {
			return true
		}
	}
	return false
}

// =============================================================================
// Middleware: CORS
// =============================================================================

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Idempotency-Key, X-Request-ID")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// =============================================================================
// Middleware: Rate Limiting
// =============================================================================

func rateLimitMiddleware(lim *slidingWindowLimiter, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := extractIP(r)
		allowed, blockedUntil, count := lim.allow(ip)

		w.Header().Set("X-RateLimit-Limit", fmt.Sprintf("%d", lim.maxRequests))
		w.Header().Set("X-RateLimit-Window", fmt.Sprintf("%ds", int(lim.window.Seconds())))
		w.Header().Set("X-RateLimit-Remaining", fmt.Sprintf("%d", maxInt(0, lim.maxRequests-count)))

		if !allowed {
			retryAfter := int(time.Until(blockedUntil).Seconds())
			w.Header().Set("Retry-After", fmt.Sprintf("%d", retryAfter))
			w.Header().Set("X-RateLimit-Remaining", "0")
			w.Header().Set("X-RateLimit-Reset", blockedUntil.UTC().Format(time.RFC3339))
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusTooManyRequests)
			json.NewEncoder(w).Encode(map[string]any{
				"error":        "Too many requests — your IP has been temporarily blocked",
				"ip":           ip,
				"blockedUntil": blockedUntil.UTC().Format(time.RFC3339),
				"retryAfter":   fmt.Sprintf("%ds", retryAfter),
			})
			return
		}
		next.ServeHTTP(w, r)
	})
}

// =============================================================================
// Middleware: JWT Authentication
// =============================================================================

func jwtMiddleware(secret string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if isPublic(r.URL.Path) {
			next.ServeHTTP(w, r)
			return
		}
		authHeader := r.Header.Get("Authorization")
		tokenStr, found := strings.CutPrefix(authHeader, "Bearer ")
		if !found || tokenStr == "" {
			writeJSON(w, http.StatusUnauthorized, map[string]string{
				"error": "Missing or malformed Authorization header — expected: Bearer <token>",
			})
			return
		}
		_, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
			}
			return []byte(secret), nil
		})
		if err != nil {
			writeJSON(w, http.StatusUnauthorized, map[string]string{
				"error": "Invalid or expired JWT token",
			})
			return
		}
		next.ServeHTTP(w, r)
	})
}

// =============================================================================
// Middleware: Request Logging
// =============================================================================

func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		lrw := &statusRecorder{ResponseWriter: w, statusCode: http.StatusOK}
		next.ServeHTTP(lrw, r)

		icon := "✅"
		switch {
		case lrw.statusCode == http.StatusTooManyRequests:
			icon = "🚫"
		case lrw.statusCode >= 500:
			icon = "💥"
		case lrw.statusCode >= 400:
			icon = "⚠️ "
		}
		log.Printf("%s %-6s %-50s %d  %v  ip=%s",
			icon, r.Method, r.URL.Path, lrw.statusCode, time.Since(start), extractIP(r))
	})
}

type statusRecorder struct {
	http.ResponseWriter
	statusCode int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.statusCode = code
	r.ResponseWriter.WriteHeader(code)
}

// =============================================================================
// Helpers
// =============================================================================

func extractIP(r *http.Request) string {
	if ip := r.Header.Get("X-Real-IP"); ip != "" {
		return strings.TrimSpace(ip)
	}
	if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
		return strings.TrimSpace(strings.SplitN(fwd, ",", 2)[0])
	}
	ip := r.RemoteAddr
	if i := strings.LastIndex(ip, ":"); i != -1 {
		ip = ip[:i]
	}
	return ip
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(body)
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// =============================================================================
// Reverse Proxy factory
//
// Adds X-Gateway and X-Real-IP headers to every proxied request so
// downstream services can identify the request origin.
// =============================================================================

func newProxy(target string) http.Handler {
	if target == "" {
		// Return a 503 handler for unconfigured upstreams
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{
				"error": "upstream not configured",
			})
		})
	}
	u, err := url.Parse(target)
	if err != nil {
		log.Fatalf("invalid upstream %q: %v", target, err)
	}
	proxy := httputil.NewSingleHostReverseProxy(u)

	// Preserve original Director and add gateway headers
	orig := proxy.Director
	proxy.Director = func(req *http.Request) {
		orig(req)
		req.Header.Set("X-Gateway", "titan-gateway-go")
		req.Header.Set("X-Real-IP", extractIP(req))
		req.Header.Set("X-Forwarded-Host", req.Host)
	}

	// Return a clean 502 on upstream errors instead of an empty response
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		log.Printf("💥 upstream error  target=%s  path=%s  err=%v", target, r.URL.Path, err)
		writeJSON(w, http.StatusBadGateway, map[string]string{
			"error":    "upstream service unavailable",
			"upstream": target,
		})
	}
	return proxy
}

// =============================================================================
// Route registration helper — registers both /prefix and /prefix/ so Go's
// default ServeMux handles subtree matching correctly.
// =============================================================================

func handle(mux *http.ServeMux, pattern string, handler http.Handler) {
	mux.Handle(pattern, handler)
	if !strings.HasSuffix(pattern, "/") {
		mux.Handle(pattern+"/", handler)
	}
}

// =============================================================================
// Main
// =============================================================================

func main() {
	cfg := loadConfig()

	lim := newSlidingWindowLimiter(
		cfg.RateLimit.MaxRequests,
		cfg.RateLimit.WindowSeconds,
		cfg.RateLimit.BlockMinutes,
	)
	log.Printf("🛡️  Rate limit: %d req / %ds window — violators blocked %dm",
		cfg.RateLimit.MaxRequests, cfg.RateLimit.WindowSeconds, cfg.RateLimit.BlockMinutes)

	// ── Upstream proxies ──────────────────────────────────────────────────────
	coreBanking  := newProxy(cfg.Upstreams.CoreBanking)
	notification := newProxy(cfg.Upstreams.Notification)
	promotion    := newProxy(cfg.Upstreams.Promotion)
	loans        := newProxy(cfg.Upstreams.Loans)
	aiService    := newProxy(cfg.Upstreams.AIService)
	flService    := newProxy(cfg.Upstreams.FederatedLearning)
	qkdService   := newProxy(cfg.Upstreams.QKDService)

	mux := http.NewServeMux()

	// ─────────────────────────────────────────────────────────────────────────
	// Gateway management endpoints (never proxied, served locally)
	// ─────────────────────────────────────────────────────────────────────────

	// GET /health — liveness probe used by Docker healthcheck and load balancers
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"status":    "UP",
			"service":   "titan-gateway-go",
			"timestamp": time.Now().UTC().Format(time.RFC3339),
			"upstreams": map[string]string{
				"core_banking":       cfg.Upstreams.CoreBanking,
				"notification":       cfg.Upstreams.Notification,
				"promotion":          cfg.Upstreams.Promotion,
				"loans":              cfg.Upstreams.Loans,
				"ai_service":         cfg.Upstreams.AIService,
				"federated_learning": cfg.Upstreams.FederatedLearning,
				"qkd_service":        cfg.Upstreams.QKDService,
			},
			"rateLimit": map[string]any{
				"maxRequests":   cfg.RateLimit.MaxRequests,
				"windowSeconds": cfg.RateLimit.WindowSeconds,
				"blockMinutes":  cfg.RateLimit.BlockMinutes,
			},
		})
	})

	// GET /health/rate-limits — shows currently blocked IPs (admin / debugging)
	mux.HandleFunc("/health/rate-limits", func(w http.ResponseWriter, r *http.Request) {
		blocked := lim.blockedIPs()
		writeJSON(w, http.StatusOK, map[string]any{
			"blockedCount": len(blocked),
			"blockedIPs":   blocked,
		})
	})

	// GET /routes — self-describing route table (useful during development)
	mux.HandleFunc("/routes", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"gateway": "titan-gateway-go",
			"port":    cfg.Server.Port,
			"routes": []map[string]string{
				// ── core-banking ───────────────────────────────────────────
				{"path": "/api/v1/auth/**",                   "upstream": cfg.Upstreams.CoreBanking,  "auth": "public"},
				{"path": "/api/auth/otp/**",                  "upstream": cfg.Upstreams.CoreBanking,  "auth": "public"},
				{"path": "/api/v1/accounts/**",               "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/v1/transactions/**",           "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/v1/atm/**",                    "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/v1/qr/**",                     "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/v1/users/**",                  "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/v1/notifications/**",          "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/v1/scheduled-transactions/**", "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/v1/fixed-deposits/**",         "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/v1/statements/**",             "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/admin/outbox/**",              "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				{"path": "/api/demo/**",                      "upstream": cfg.Upstreams.CoreBanking,  "auth": "jwt"},
				// ── notifications ─────────────────────────────────────────
				{"path": "/api/notify/**",                    "upstream": cfg.Upstreams.Notification, "auth": "jwt"},
				{"path": "/api/audit/**",                     "upstream": cfg.Upstreams.Notification, "auth": "jwt"},
				{"path": "/api/preferences/**",               "upstream": cfg.Upstreams.Notification, "auth": "jwt"},
				{"path": "/api/webhooks/**",                  "upstream": cfg.Upstreams.Notification, "auth": "public"},
				{"path": "/webhooks/**",                      "upstream": cfg.Upstreams.Notification, "auth": "public"},
				{"path": "/chaos/**",                         "upstream": cfg.Upstreams.Notification, "auth": "jwt"},
				// ── promotions ────────────────────────────────────────────
				{"path": "/api/v1/promotions/**",             "upstream": cfg.Upstreams.Promotion,    "auth": "jwt"},
				{"path": "/promotions/**",                    "upstream": cfg.Upstreams.Promotion,    "auth": "jwt"},
				{"path": "/api/quests/**",                    "upstream": cfg.Upstreams.Promotion,    "auth": "jwt"},
				{"path": "/api/referrals/**",                 "upstream": cfg.Upstreams.Promotion,    "auth": "jwt"},
				{"path": "/api/merchant/**",                  "upstream": cfg.Upstreams.Promotion,    "auth": "jwt"},
				{"path": "/api/shadow/**",                    "upstream": cfg.Upstreams.Promotion,    "auth": "jwt"},
				{"path": "/admin/campaigns/**",               "upstream": cfg.Upstreams.Promotion,    "auth": "jwt"},
				// ── loans ─────────────────────────────────────────────────
				{"path": "/api/v1/loans/**",                  "upstream": cfg.Upstreams.Loans,        "auth": "jwt"},
				// ── ai / edge ─────────────────────────────────────────────
				{"path": "/api/ai/**",                        "upstream": cfg.Upstreams.AIService,    "auth": "jwt"},
				// ── federated learning ────────────────────────────────────
				{"path": "/api/v1/fl/**",                     "upstream": cfg.Upstreams.FederatedLearning, "auth": "jwt"},
				// ── qkd security ──────────────────────────────────────────
				{"path": "/api/v1/qkd/**",                    "upstream": cfg.Upstreams.QKDService,   "auth": "jwt"},
			},
		})
	})

	// ─────────────────────────────────────────────────────────────────────────
	// titan-core-banking routes
	//   Controllers: AuthenticationController, AccountController,
	//   TransactionController, AtmController, QrPaymentController,
	//   UserController, DeviceTokenController, ScheduledTransactionController,
	//   FixedDepositController, StatementController, LoanProxyController,
	//   LoanDisbursementController, LoanFeeController, OtpController,
	//   OutboxMonitoringController, DemoController
	// ─────────────────────────────────────────────────────────────────────────
	handle(mux, "/api/v1/auth",                   coreBanking) // login, register
	handle(mux, "/api/auth/otp",                  coreBanking) // OTP generate (public)
	handle(mux, "/api/v1/accounts",               coreBanking)
	handle(mux, "/api/v1/transactions",           coreBanking) // transfer, deposit, withdraw, international, internal/*
	handle(mux, "/api/v1/atm",                    coreBanking) // generate, redeem, cancel, status
	handle(mux, "/api/v1/qr",                     coreBanking) // generate, pay, generate-payer, collect
	handle(mux, "/api/v1/users",                  coreBanking)
	handle(mux, "/api/v1/notifications",          coreBanking) // device-token, internal device-tokens
	handle(mux, "/api/v1/scheduled-transactions", coreBanking)
	handle(mux, "/api/v1/fixed-deposits",         coreBanking)
	handle(mux, "/api/v1/statements",             coreBanking)
	handle(mux, "/api/admin/outbox",              coreBanking) // outbox monitoring
	handle(mux, "/api/demo",                      coreBanking) // test-connection

	// ─────────────────────────────────────────────────────────────────────────
	// titan-loans-service routes
	//   The LoanController on loans-service owns /api/v1/loans at :8085.
	//   LoanProxyController on core-banking forwards to loans-service internally,
	//   but the gateway sends the request directly to loans-service for efficiency.
	// ─────────────────────────────────────────────────────────────────────────
	handle(mux, "/api/v1/loans", loans) // apply, approve, reject, get by ID

	// ─────────────────────────────────────────────────────────────────────────
	// titan-notifications-service routes
	//   Controllers: NotifyController, AuditController, UserPreferenceController,
	//   WebhookController, InboundWebhookController, ChaosController
	// ─────────────────────────────────────────────────────────────────────────
	handle(mux, "/api/notify",      notification)
	handle(mux, "/api/audit",       notification)
	handle(mux, "/api/preferences", notification)
	handle(mux, "/api/webhooks",    notification) // outbound webhooks
	handle(mux, "/webhooks",        notification) // inbound webhooks (external providers)
	handle(mux, "/chaos",           notification) // chaos engineering endpoints

	// ─────────────────────────────────────────────────────────────────────────
	// titan-promotions-service routes
	//   Controllers: DepositPromotionController, QuestController,
	//   ReferralController, MerchantFederationController, ShadowRuleController,
	//   CampaignAdminController, PromotionGraphQLController, LeaderboardController
	//
	//   Key endpoints:
	//     POST /promotions/deposit/apply     — trigger $100→$2 deposit bonus
	//     POST /promotions/deposit/simulate  — dry-run eligibility check
	//     GET  /promotions/deposit/status    — campaign status
	// ─────────────────────────────────────────────────────────────────────────
	handle(mux, "/api/v1/promotions", promotion) // v1-prefixed (future)
	handle(mux, "/promotions",        promotion) // direct promotion paths
	handle(mux, "/api/quests",        promotion)
	handle(mux, "/api/referrals",     promotion)
	handle(mux, "/api/merchant",      promotion)
	handle(mux, "/api/shadow",        promotion)
	handle(mux, "/admin/campaigns",   promotion)
	handle(mux, "/graphql",           promotion) // GraphQL supergraph (promotions has GraphiQL)
	handle(mux, "/graphiql",          promotion) // GraphiQL IDE

	// ─────────────────────────────────────────────────────────────────────────
	// titan-edge-ai / titan-ai-service routes
	// ─────────────────────────────────────────────────────────────────────────
	handle(mux, "/api/ai", aiService)

	// ─────────────────────────────────────────────────────────────────────────
	// titan-federated-learning routes
	// ─────────────────────────────────────────────────────────────────────────
	handle(mux, "/api/v1/fl", flService)

	// ─────────────────────────────────────────────────────────────────────────
	// titan-qkd-service routes
	// ─────────────────────────────────────────────────────────────────────────
	handle(mux, "/api/v1/qkd", qkdService)

	// ── Middleware chain ──────────────────────────────────────────────────────
	// Request flow: logging → CORS → rate-limit → JWT → mux
	handler := loggingMiddleware(
		corsMiddleware(
			rateLimitMiddleware(lim,
				jwtMiddleware(cfg.Auth.JWTSecret, mux),
			),
		),
	)

	// ── Server with graceful shutdown ─────────────────────────────────────────
	addr := "0.0.0.0:" + cfg.Server.Port
	srv := &http.Server{
		Addr:         addr,
		Handler:      handler,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 60 * time.Second,
		IdleTimeout:  120 * time.Second,
	}

	// Print startup banner
	log.Printf("🚀 titan-gateway-go  listening on %s", addr)
	log.Printf("   core-banking       → %s", cfg.Upstreams.CoreBanking)
	log.Printf("   notifications      → %s", cfg.Upstreams.Notification)
	log.Printf("   promotions         → %s", cfg.Upstreams.Promotion)
	log.Printf("   loans              → %s", cfg.Upstreams.Loans)
	log.Printf("   ai-service         → %s", cfg.Upstreams.AIService)
	log.Printf("   federated-learning → %s", cfg.Upstreams.FederatedLearning)
	log.Printf("   qkd-service        → %s", cfg.Upstreams.QKDService)
	log.Printf("   routes reference   → http://localhost:%s/routes", cfg.Server.Port)

	// Start in background goroutine so we can listen for OS signals
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	// Graceful shutdown on SIGTERM / SIGINT (Docker stop, Ctrl-C)
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGTERM, syscall.SIGINT)
	<-quit
	log.Println("⏳ Shutting down gracefully (30s timeout)…")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("⚠️  Shutdown error: %v", err)
	}
	log.Println("👋 titan-gateway-go stopped")
}
