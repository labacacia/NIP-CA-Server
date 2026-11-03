// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nipcaserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP/JSON/SQLite/Ed25519 coverage for the Boot 4 / Jackson 3 web migration.
 * The SDK's Jackson 2 signing/storage representation must retain its exact signature.
 * This synthetic plaintext loopback test does not qualify production transport/PKI.
 */
class SecurityMigrationTests {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Test
    void startsAndPreservesAuthorizedSignedIssuanceAndDuplicateState() throws Exception {
        String operator = UUID.randomUUID().toString();
        String[] options = {
            "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
            "--nip.ca.nid=urn:nps:org:fixture:ca", "--nip.ca.base-url=http://127.0.0.1",
            "--nip.ca.passphrase=" + UUID.randomUUID(), "--nip.ca.operator-api-key=" + operator,
            "--nip.ca.key-file=" + directory.resolve("ca.key.enc"),
            "--nip.ca.root-cert-file=" + directory.resolve("ca.root.der"),
            "--nip.ca.db-path=" + directory.resolve("ca.sqlite"),
        };
        byte[] originalCaKey;
        String registeredSerial;
        try (ConfigurableApplicationContext context = SpringApplication.run(NipCaApplication.class, options)) {
            Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
            assertNotNull(port);
            originalCaKey = context.getBean(NipCaApplication.CaState.class).publicKey.getEncoded();
            URI root = URI.create("http://127.0.0.1:" + port);
            HttpClient client = HttpClient.newHttpClient();
            assertEquals(200, client.send(HttpRequest.newBuilder(root.resolve("/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
            var subject = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String nid = "urn:nps:agent:fixture:migration";
            String publicKey = context.getBean(CaService.class).pubKeyString(subject.getPublic());
            String body = mapper.writeValueAsString(Map.of("nid", nid, "pub_key", publicKey,
                "capabilities", java.util.List.of("nwp:action"), "scope", Map.of("z_limit", 17, "a_note", "退款 café", "nodes", java.util.List.of("nwp://fixture"), "nested", Map.of("z", 2, "a", 1))));
            var unauthenticated = client.send(register(root, body, null), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthenticated.statusCode());
            assertTrue(context.getBean(DbService.class).getActive(nid).isEmpty());
            var issued = client.send(register(root, body, operator), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, issued.statusCode());
            var response = mapper.readTree(issued.body());
            registeredSerial = response.path("serial").asText();
            assertEquals(nid, response.path("nid").asText());
            @SuppressWarnings("unchecked")
            Map<String, Object> ident = mapper.convertValue(response.path("ident_frame"), Map.class);
            String signature = (String) ident.remove("signature");
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(context.getBean(NipCaApplication.CaState.class).publicKey);
            verifier.update(mapper.writeValueAsBytes(new TreeMap<>(ident)));
            assertTrue(verifier.verify(Base64.getDecoder().decode(signature.substring("ed25519:".length()))));
            assertEquals(409, client.send(register(root, body, operator), HttpResponse.BodyHandlers.ofString()).statusCode());
            var verified = client.send(HttpRequest.newBuilder(root.resolve("/v1/agents/" + nid + "/verify")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, verified.statusCode());
            assertTrue(mapper.readTree(verified.body()).path("valid").asBoolean());
            assertEquals(response.path("serial").asText(), context.getBean(DbService.class).getActive(nid).orElseThrow().serial);

            // The BC update must still issue a real v2 leaf through the migrated web stack.
            String x509Body = mapper.writeValueAsString(Map.of("nid", nid + "-x509", "pub_key", publicKey));
            var x509Request = HttpRequest.newBuilder(root.resolve("/v1/agents/register-x509"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + operator)
                .POST(HttpRequest.BodyPublishers.ofString(x509Body)).build();
            var x509Response = client.send(x509Request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, x509Response.statusCode());
            var chain = mapper.readTree(x509Response.body()).path("ident_frame").path("cert_chain");
            assertEquals(2, chain.size());
            var factory = java.security.cert.CertificateFactory.getInstance("X.509");
            var leaf = (java.security.cert.X509Certificate) factory.generateCertificate(new java.io.ByteArrayInputStream(
                Base64.getUrlDecoder().decode(chain.get(0).asText())));
            var rootCertificate = (java.security.cert.X509Certificate) factory.generateCertificate(new java.io.ByteArrayInputStream(
                Base64.getUrlDecoder().decode(chain.get(1).asText())));
            leaf.checkValidity();
            leaf.verify(rootCertificate.getPublicKey());
            assertArrayEquals(subject.getPublic().getEncoded(), leaf.getPublicKey().getEncoded());
            assertArrayEquals(context.getBean(NipCaApplication.CaState.class).rootCert.getEncoded(), rootCertificate.getEncoded());

        }
        // Restart the same synthetic key/SQLite state; no new CA identity or serial history.
        try (ConfigurableApplicationContext restarted = SpringApplication.run(NipCaApplication.class, options)) {
            assertArrayEquals(originalCaKey, restarted.getBean(NipCaApplication.CaState.class).publicKey.getEncoded());
            assertEquals(registeredSerial, restarted.getBean(DbService.class)
                .getActive("urn:nps:agent:fixture:migration").orElseThrow().serial);
            Path keyFile = directory.resolve("ca.key.enc");
            assertTrue(java.nio.file.Files.isReadable(keyFile));
            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
                assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), java.nio.file.Files.getPosixFilePermissions(keyFile));
        }
    }

    private static HttpRequest register(URI root, String body, String operator) {
        var request = HttpRequest.newBuilder(root.resolve("/v1/agents/register"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (operator != null) request.header("Authorization", "Bearer " + operator);
        return request.build();
    }
}
