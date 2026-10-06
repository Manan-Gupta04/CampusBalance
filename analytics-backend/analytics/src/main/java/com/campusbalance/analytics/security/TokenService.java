package com.campusbalance.analytics.security;

import com.campusbalance.analytics.model.Student;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** Issues the signed login token. The token's subject is the username; its "role" claim drives access rules. */
@Service
public class TokenService {

    static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final Duration ttl;

    public TokenService(JwtEncoder encoder, @Value("${app.jwt.ttl-hours:24}") long ttlHours) {
        this.encoder = encoder;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public String issue(Student account) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("campusbalance")
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(account.getUsername())
                .claim(ROLE_CLAIM, account.getRole())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
