package com.ladder.account;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ladder.account.dto.MeResponse;
import com.ladder.account.dto.RegisterRequest;
import com.ladder.security.CurrentUser;

import jakarta.validation.Valid;

// Login and logout themselves (POST /api/v1/auth/login, /logout) are handled
// by Spring Security's filters; see SecurityConfig.
@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {
    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        accountService.register(request);
    }

    // 401 when anonymous (SecurityConfig). The frontend calls this on every
    // page load to learn whether anyone is logged in.
    @GetMapping("/me")
    public MeResponse me(Principal principal) {
        return accountService.me(CurrentUser.idOrNull(principal));
    }
}
