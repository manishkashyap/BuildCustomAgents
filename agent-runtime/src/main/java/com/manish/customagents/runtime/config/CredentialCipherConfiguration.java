package com.manish.customagents.runtime.config;

import com.manish.customagents.contracts.CredentialCipher;
import com.manish.customagents.runtime.auth.CredentialDecryptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CredentialProperties.class)
public class CredentialCipherConfiguration {

    @Bean
    public CredentialDecryptor credentialDecryptor(CredentialProperties properties) {
        String key = properties.getEncryptionKey();
        if (key == null || key.isBlank()) {
            return CredentialDecryptor.unconfigured();
        }
        return CredentialDecryptor.configured(CredentialCipher.fromEncodedKey(key));
    }
}
