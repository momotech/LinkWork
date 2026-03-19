package com.linkwork.controller.v1;

import com.linkwork.context.UserContext;
import com.linkwork.model.dto.UserSoulResponse;
import com.linkwork.model.dto.UserSoulUpsertRequest;
import com.linkwork.service.UserSoulService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me/soul")
@RequiredArgsConstructor
public class UserSoulController {

    private final UserSoulService userSoulService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSoul() {
        String userId = UserContext.getCurrentUserId();
        UserSoulResponse soul = userSoulService.getCurrentUserSoul(userId);
        return ResponseEntity.ok(Map.of("code", 0, "data", soul));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> upsertSoul(@Valid @RequestBody UserSoulUpsertRequest request) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        UserSoulResponse soul = userSoulService.upsertCurrentUserSoul(userId, userName, request);
        return ResponseEntity.ok(Map.of("code", 0, "data", soul));
    }
}
