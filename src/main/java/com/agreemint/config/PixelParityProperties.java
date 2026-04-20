package com.agreemint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Kill-switch for the pixel-parity renderer (Inter-embedded font, measurement-driven
 * positioning, top-anchored text, rich-run table cells). Stays false while phases 0-4
 * are being built so production keeps the legacy iText default-Helvetica path; flips
 * to true in phase 5 once golden tests are green. Kept as a kill-switch for 6 months
 * post-launch per the risk register.
 */
@Component
@ConfigurationProperties(prefix = "agreemint.features.pixel-parity")
public class PixelParityProperties {

    private boolean enabled = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
