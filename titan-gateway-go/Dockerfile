FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY main.go .
RUN CGO_ENABLED=0 GOOS=linux go build -o titan-gateway .

# Use the same golang:1.22-alpine image for runtime — already cached locally,
# has wget + sh needed for HEALTHCHECK, adds only ~1MB vs a separate alpine pull.
FROM golang:1.22-alpine
WORKDIR /app
COPY --from=builder /app/titan-gateway .
COPY config.yaml .
EXPOSE 8088

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8088/health || exit 1

CMD ["/app/titan-gateway"]
