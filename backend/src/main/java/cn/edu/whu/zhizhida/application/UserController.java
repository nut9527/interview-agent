package cn.edu.whu.zhizhida.application;

import cn.edu.whu.zhizhida.auth.api.ChangePasswordRequest;
import cn.edu.whu.zhizhida.auth.api.DeleteAccountRequest;
import cn.edu.whu.zhizhida.auth.api.UserProfile;
import cn.edu.whu.zhizhida.auth.api.UserProfileRequest;
import cn.edu.whu.zhizhida.auth.api.UserService;

import cn.edu.whu.zhizhida.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

    private final UserService userService;

    private final AccountClosureService closure;

    public UserController(UserService userService, AccountClosureService closure) {
        this.closure = closure;
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<UserProfile> current() {
        return ApiResponse.ok(userService.current());
    }

    @PutMapping
    public ApiResponse<UserProfile> update(@Valid @RequestBody UserProfileRequest request) {
        return ApiResponse.ok(userService.update(request));
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(request);
        return ApiResponse.ok(null);
    }

    @DeleteMapping
    public ApiResponse<Void> deleteAccount(@Valid @RequestBody DeleteAccountRequest request) {
        closure.deleteAccount(request);
        return ApiResponse.ok(null);
    }
}
