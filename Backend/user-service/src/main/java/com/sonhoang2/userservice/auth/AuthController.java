package com.sonhoang2.userservice.auth;

import com.sonhoang2.common.dto.JSendResponse;
import com.sonhoang2.common.exception.RateLimitExceededException;
import com.sonhoang2.userservice.auth.dto.LoginRequest;
import com.sonhoang2.userservice.auth.dto.LoginResponse;
import com.sonhoang2.userservice.auth.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthRateLimitService authRateLimitService;

    @PostMapping("/login")
    public ResponseEntity<JSendResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                               HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        long retryAfter = authRateLimitService.checkRateLimit(ip);
        if (retryAfter > 0) {
            throw new RateLimitExceededException("Too many login attempts. Try again later.", retryAfter);
        }
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(JSendResponse.success(response));
    }

    @PostMapping("/signup")
    public ResponseEntity<JSendResponse<LoginResponse>> signup(@Valid @RequestBody RegisterRequest request,
                                                               HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        long retryAfter = authRateLimitService.checkRateLimit(ip);
        if (retryAfter > 0) {
            throw new RateLimitExceededException("Too many signup attempts. Try again later.", retryAfter);
        }
        LoginResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(JSendResponse.success(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<JSendResponse<Map<String, String>>> logout() {
        authService.logout();
        return ResponseEntity.ok(JSendResponse.success(Map.of("message", "Logged out successfully")));
    }
}

