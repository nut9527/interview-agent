package cn.edu.whu.zhizhida.resume.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("resumes")
public class ResumeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String ownerName;
    private String targetRole;
    private String summary;
    private String skillsJson;
    private String educationJson;
    private String projectsJson;
    private String internshipsJson;
    private String certificatesJson;
    private String templateKey;
    private Long revision;
    private String detailsJson;
}
