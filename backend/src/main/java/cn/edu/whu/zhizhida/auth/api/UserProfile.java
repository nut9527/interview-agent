package cn.edu.whu.zhizhida.auth.api;

import java.util.List;

public record UserProfile(
        Long id,
        String username,
        String displayName,
        String targetRole,
        List<String> skillTags,
        boolean demo
) {

    public UserProfile(Long id, String username, String displayName, String targetRole, List<String> skillTags) {
        this(id, username, displayName, targetRole, skillTags, false);
    }
}
