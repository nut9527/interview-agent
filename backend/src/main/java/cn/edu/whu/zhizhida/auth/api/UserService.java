package cn.edu.whu.zhizhida.auth.api;

public interface UserService {

    UserProfile current();

    UserProfile update(UserProfileRequest request);

    void changePassword(ChangePasswordRequest request);


}
