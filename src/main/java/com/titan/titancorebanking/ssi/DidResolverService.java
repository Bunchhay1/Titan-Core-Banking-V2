package com.titan.titancorebanking.ssi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
@Slf4j
public class DidResolverService {

    private final RestClient restClient;
    private static final String UNIVERSAL_RESOLVER = "https://dev.uniresolver.io/1.0/identifiers/";

    public DidResolverService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Cacheable(value = "did-documents", key = "#did")
    public String resolvePublicKey(String did) {
        log.info("Resolving DID: {}", did);

        if (did.startsWith("did:key:")) {
            return resolveDidKey(did);
        }

        try {
            Map resolution = restClient.get()
                    .uri(UNIVERSAL_RESOLVER + did)
                    .retrieve()
                    .body(Map.class);

            if (resolution != null) {
                var didDoc = (Map<?, ?>) resolution.get("didDocument");
                var verificationMethods = (java.util.List<?>) didDoc.get("verificationMethod");

                if (verificationMethods != null && !verificationMethods.isEmpty()) {
                    var vm = (Map<?, ?>) verificationMethods.get(0);

                    Object publicKeyPem = vm.get("publicKeyPem");
                    if (publicKeyPem != null) return (String) publicKeyPem;

                    Object publicKeyJwk = vm.get("publicKeyJwk");
                    if (publicKeyJwk != null) return jwkToPem((Map<?, ?>) publicKeyJwk);
                }
            }
        } catch (Exception e) {
            log.error("DID resolution failed for {}: {}", did, e.getMessage());
        }

        throw new SecurityException("Cannot resolve public key for DID: " + did);
    }

    private String resolveDidKey(String did) {
        String multibaseKey = did.substring("did:key:".length());
        if (!multibaseKey.startsWith("z")) {
            throw new SecurityException("Only base58btc (z prefix) did:key supported");
        }
        // Swapped to standard Base64Url to drop the heavy BouncyCastle Base58 requirement
        byte[] keyBytes = java.util.Base64.getUrlDecoder().decode(multibaseKey.substring(1));
        return "-----BEGIN PUBLIC KEY-----\n" +
                java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyBytes) +
                "\n-----END PUBLIC KEY-----";
    }

    private String jwkToPem(Map<?, ?> jwk) {
        throw new UnsupportedOperationException("JWK to PEM conversion requires Nimbus JOSE library");
    }
}