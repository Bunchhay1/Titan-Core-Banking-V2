package com.titan.titancorebanking.ssi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Task 6: Self-Sovereign Identity (SSI) & W3C Verifiable Credentials
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerifiableCredentialService {

    private final ObjectMapper objectMapper;
    private final DidResolverService didResolverService;

    public VerifiedIdentity verifyCredential(String vcJson) throws Exception {
        JsonNode vc = objectMapper.readTree(vcJson);

        validateVcStructure(vc);

        String expirationDate = vc.path("expirationDate").asText(null);
        if (expirationDate != null && Instant.parse(expirationDate).isBefore(Instant.now())) {
            throw new SecurityException("Verifiable Credential has expired");
        }

        String issuerDid = vc.path("issuer").asText();
        String issuerPublicKeyPem = didResolverService.resolvePublicKey(issuerDid);

        JsonNode proof = vc.path("proof");
        String proofType = proof.path("type").asText();
        String proofValue = proof.path("proofValue").asText();

        JsonNode vcWithoutProof = removeProof(vc);
        String canonicalDoc = objectMapper.writeValueAsString(vcWithoutProof);

        boolean valid = switch (proofType) {
            case "Ed25519Signature2020" -> verifyEd25519(canonicalDoc, proofValue, issuerPublicKeyPem);
            case "RsaSignature2018"     -> verifyRsa(canonicalDoc, proofValue, issuerPublicKeyPem);
            default -> throw new SecurityException("Unsupported proof type: " + proofType);
        };

        if (!valid) {
            throw new SecurityException("Verifiable Credential signature is INVALID");
        }

        JsonNode subject = vc.path("credentialSubject");
        VerifiedIdentity identity = new VerifiedIdentity(
                subject.path("id").asText(),
                subject.path("givenName").asText(),
                subject.path("familyName").asText(),
                subject.path("dateOfBirth").asText(),
                subject.path("nationality").asText(),
                subject.path("nationalId").asText(),
                issuerDid,
                vc.path("id").asText(),
                true
        );

        log.info("  VC verified for subject={} issued by={}", identity.subjectDid(), issuerDid);
        return identity;
    }

    private void validateVcStructure(JsonNode vc) {
        List<String> required = List.of("@context", "type", "issuer", "issuanceDate", "credentialSubject", "proof");
        for (String field : required) {
            if (vc.path(field).isMissingNode()) {
                throw new IllegalArgumentException("Missing required VC field: " + field);
            }
        }

        boolean hasVcType = false;
        for (JsonNode t : vc.path("type")) {
            if ("VerifiableCredential".equals(t.asText())) { hasVcType = true; break; }
        }
        if (!hasVcType) throw new IllegalArgumentException("Not a VerifiableCredential");
    }

    private boolean verifyEd25519(String document, String proofValue, String publicKeyPem) throws Exception {
        byte[] docBytes = document.getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = Base64.getDecoder().decode(proofValue);
        byte[] pubKeyBytes = pemToBytes(publicKeyPem);

        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(pubKey);
        sig.update(docBytes);
        return sig.verify(sigBytes);
    }

    private boolean verifyRsa(String document, String proofValue, String publicKeyPem) throws Exception {
        byte[] docBytes = document.getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = Base64.getDecoder().decode(proofValue);
        byte[] pubKeyBytes = pemToBytes(publicKeyPem);

        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(pubKey);
        sig.update(docBytes);
        return sig.verify(sigBytes);
    }

    private byte[] pemToBytes(String pem) {
        String stripped = pem.replaceAll("-----.*-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(stripped);
    }

    private JsonNode removeProof(JsonNode vc) {
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode) vc.deepCopy();
        copy.remove("proof");
        return copy;
    }

    public record VerifiedIdentity(
            String subjectDid,
            String givenName,
            String familyName,
            String dateOfBirth,
            String nationality,
            String nationalId,
            String issuerDid,
            String credentialId,
            boolean amlCleared
    ) {}
}