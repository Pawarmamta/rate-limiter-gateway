package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The password a genuine client must send. Change it in application.yml. */
@ConfigurationProperties(prefix = "gateway.auth")
public record AuthProperties(@DefaultValue("secret123") String password) {
}