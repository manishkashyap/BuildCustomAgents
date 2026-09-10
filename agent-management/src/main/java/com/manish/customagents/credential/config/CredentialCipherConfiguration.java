package com.manish.customagents.credential.config;

import com.manish.customagents.contracts.CredentialCipher;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CredentialEncryptionProperties.class)
public class CredentialCipherConfiguration {

    /**
     * Built eagerly so a missing or malformed key fails at startup rather than on the first attempt
     * to save a credential.
     */
    @Bean
    public CredentialCipher credentialCipher(CredentialEncryptionProperties properties) {
        return CredentialCipher.fromEncodedKey(properties.getEncryptionKey());
    }
}
