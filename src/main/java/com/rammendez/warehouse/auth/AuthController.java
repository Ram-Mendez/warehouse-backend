package com.rammendez.warehouse.auth;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    @SecurityRequirements
    public AuthDtos.Tokens login(@Valid @RequestBody AuthDtos.Login input) {
        return service.login(input);
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public AuthDtos.Tokens rotateRefreshTokenOrRevokeSessionOnReuse(@Valid @RequestBody AuthDtos.Refresh input) {
        return service.rotateRefreshTokenOrRevokeSessionOnReuse(input.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeCurrentAuthSessionAndRefreshTokens() {
        service.revokeCurrentAuthSessionAndRefreshTokens();
    }

    @GetMapping("/me")
    public AuthDtos.Me getCurrentUserProfileRolesAndPermissions() {
        return service.getCurrentUserProfileRolesAndPermissions();
    }
}
