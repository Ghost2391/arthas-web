package com.example.arthasweb.mirror;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/arthas")
public class ArthasMirrorController {

    private static final Log log = LogFactory.getLog(ArthasMirrorController.class);

    @Value("${arthas.version:}")
    private String configuredVersion;

    @Value("${arthas.external-dist-dir:}")
    private String externalDistDir;

    @GetMapping("/api/latest_version")
    public ResponseEntity<String> latestVersion() {
        String version = resolveVersion();
        if (version == null || version.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(version);
    }

    @GetMapping("/download/{version}")
    public ResponseEntity<Resource> download(@PathVariable String version) {
        String zipName = "arthas-packaging-" + version + "-bin.zip";

        Resource resource = null;

        if (externalDistDir != null && !externalDistDir.isEmpty()) {
            File externalFile = Paths.get(externalDistDir, zipName).toFile();
            if (externalFile.exists() && externalFile.isFile()) {
                resource = new FileSystemResource(externalFile);
            }
        }

        if (resource == null) {
            String classpathPath = "static/arthas/" + zipName;
            ClassPathResource cpResource = new ClassPathResource(classpathPath);
            if (cpResource.exists()) {
                resource = cpResource;
            }
        }

        if (resource == null) {
            log.warn("arthas dist not found for version=" + version
                    + " (searched external=" + externalDistDir + " and classpath:static/arthas/)");
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + zipName + "\"")
                .body(resource);
    }

    private String resolveVersion() {
        if (configuredVersion != null && !configuredVersion.isEmpty()) {
            return configuredVersion;
        }

        ClassPathResource versionFile = new ClassPathResource("static/arthas/version.txt");
        if (versionFile.exists()) {
            try (InputStream is = versionFile.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                log.warn("failed to read version.txt from classpath", e);
            }
        }

        return null;
    }
}
