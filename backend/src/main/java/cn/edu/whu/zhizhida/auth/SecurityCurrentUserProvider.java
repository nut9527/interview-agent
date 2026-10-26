package cn.edu.whu.zhizhida.auth;

import cn.edu.whu.zhizhida.auth.api.AuthenticatedUser;
import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;

import cn.edu.whu.zhizhida.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityCurrentUserProvider implements CurrentUserProvider {

    @Override
    public Long requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.userId();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
}
