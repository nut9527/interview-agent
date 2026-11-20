package cn.edu.whu.zhizhida.jobs.api;

import java.time.LocalDate;
import java.util.List;

import cn.edu.whu.zhizhida.jobs.api.TargetJob.Requirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record TargetJobRequest(
                @NotNull @PositiveOrZero Long revision,
                @NotBlank @Size(max = 160) String title,
                @Size(max = 160) String company,
                @Size(max = 160) String sourceName,
                @Size(max = 1000) String sourceUrl,
                LocalDate deadline,
                @NotBlank @Size(max = 20000) String jdText,
                @NotNull @Size(max = 100) List<@NotNull @Valid Requirement> requirements) {
}
