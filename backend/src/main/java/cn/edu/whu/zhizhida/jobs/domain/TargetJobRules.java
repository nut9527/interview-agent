package cn.edu.whu.zhizhida.jobs.domain;

import cn.edu.whu.zhizhida.common.text.SkillLexicon;
import cn.edu.whu.zhizhida.jobs.api.TargetJob;
import cn.edu.whu.zhizhida.jobs.api.TargetJob.*;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import java.util.*;
import java.util.regex.Pattern;

/** 保守提取与原文定位，不判断应聘者能力或录用概率。 */
public final class TargetJobRules {
    public static final String VERSION = "target-jd-v1";
    private static final Pattern BONUS = Pattern.compile("优先|加分|加分项|非必需|非必须|不要求|不强制|preferred|nice.to.have|optional", Pattern.CASE_INSENSITIVE);
    private static final Pattern MUST = Pattern.compile("必须|必备|至少|要求|熟悉|熟练|掌握|具备|must|required|proficien", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNCERTAIN = Pattern.compile("待定|待确认|不限|面议|未说明|unknown|tbd", Pattern.CASE_INSENSITIVE);
    private TargetJobRules() {}

    public static Extraction extract(String jd) {
        var requirements = new ArrayList<Requirement>();
        var warnings = new ArrayList<String>();
        Kind section = Kind.UNKNOWN;
        // 不按英文句点拆分，保留 Node.js、.NET 等名称。
        for (String line : jd.split("[\\r\\n；;。]+")) {
            String text = line.strip().replaceFirst("^(?:[-*•]\\s*|[（(]?\\d+[、.)）]\\s*)", "");
            if (text.isBlank()) continue;
            String heading = text.replaceFirst("[：:].*$", "").strip().toLowerCase(Locale.ROOT);
            Kind headingKind = switch (heading) {
                case "任职要求", "任职资格", "岗位要求", "必备要求", "基本要求", "requirements", "minimum qualifications" -> Kind.MUST;
                case "加分项", "优先条件", "优先要求", "preferred qualifications", "nice to have" -> Kind.BONUS;
                case "岗位职责", "工作职责", "福利待遇", "responsibilities", "benefits" -> Kind.UNKNOWN;
                default -> null;
            };
            if (headingKind != null) {
                section = headingKind;
                if (!text.contains(":") && !text.contains("：")) continue;
                text = text.replaceFirst("^[^：:]*[：:]", "").strip();
                if (text.isBlank()) continue;
            }
            // 混合一句时保留原文并标未知，防止把加分项提升为硬门槛。
            for (String clause : text.split("[，,](?=\\s*(?:必须|必备|至少|有.+?(?:优先|加分)))")) {
                String source = clause.strip();
                if (source.isBlank()) continue;
                Kind kind = UNCERTAIN.matcher(source).find() ? Kind.UNKNOWN
                        : BONUS.matcher(source).find() ? Kind.BONUS
                        : section == Kind.BONUS ? Kind.BONUS
                        : MUST.matcher(source).find() ? Kind.MUST : section;
                if (BONUS.matcher(source).find() && (source.contains("必须") || source.contains("must "))) kind = Kind.UNKNOWN;
                for (int offset = 0; offset < source.length(); offset += 1000) {
                    if (requirements.size() == 100) {
                        warnings.add("仅提取前 100 条；请对照完整 JD 手动补充或合并要求。");
                        return new Extraction(requirements, warnings, VERSION);
                    }
                    String quote = source.substring(offset, Math.min(source.length(), offset + 1000));
                    var keywords = SkillLexicon.knownSkills().stream().sorted()
                            .filter(skill -> SkillLexicon.contains(quote, skill)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                    for (String keyword : List.of("本科", "硕士", "博士", "CET-4", "CET-6", "英语", "每周", "毕业")) {
                        if (SkillLexicon.contains(quote, keyword)) keywords.add(keyword);
                    }
                    keywords = new ArrayList<>(keywords.stream().limit(20).toList());
                    requirements.add(new Requirement("r" + (requirements.size() + 1), quote, kind, keywords, quote));
                }
            }
        }
        warnings.add("提取基于标题和措辞规则；请检查分类、遗漏与检索词。未明确的条件保留为未知。");
        return new Extraction(requirements, warnings, VERSION);
    }

    public static Analysis analyze(TargetJob job, ResumeProfile resume) {
        var fields = evidenceFields(resume);
        var matches = job.requirements().stream().map(requirement -> {
            var evidence = new ArrayList<Evidence>();
            var referenced = new HashSet<String>();
            for (Evidence field : fields) {
                var hits = requirement.keywords().stream().filter(k -> SkillLexicon.contains(field.quote(), k)).toList();
                if (!hits.isEmpty()) {
                    referenced.addAll(hits);
                    evidence.add(new Evidence(field.fieldPath(), field.label(), field.quote(), hits));
                }
            }
            var missing = requirement.keywords().stream().filter(k -> !referenced.contains(k)).toList();
            return new RequirementMatch(requirement, evidence.isEmpty() ? "UNKNOWN" : "REFERENCED",
                    evidence.stream().limit(8).toList(), missing);
        }).toList();
        var unknown = new ArrayList<String>();
        if (job.company() == null || job.company().isBlank()) unknown.add("公司未填写");
        if ((job.sourceName() == null || job.sourceName().isBlank()) && (job.sourceUrl() == null || job.sourceUrl().isBlank())) unknown.add("来源未填写");
        if (job.deadline() == null) unknown.add("截止日期未说明");
        unknown.add("原文关联仅供核对；熟练程度、年限、学历门槛及组合条件需逐条确认。未找到证据不等于不符合要求。");
        return new Analysis(job.id(), job.revision(), resume.id(), resume.revision(), VERSION, matches, unknown);
    }

    private static List<Evidence> evidenceFields(ResumeProfile resume) {
        var fields = new ArrayList<Evidence>();
        add(fields, "summary", "个人简介", resume.summary());
        addAll(fields, "skills", "技能声明", resume.skills());
        addAll(fields, "projectExperiences", "项目经历", resume.projectExperiences());
        addAll(fields, "internshipExperiences", "实习经历", resume.internshipExperiences());
        addAll(fields, "educationExperiences", "教育经历", resume.educationExperiences());
        addAll(fields, "certificates", "证书", resume.certificates());
        var details = resume.details();
        add(fields, "details.graduationDate", "毕业时间", details.graduationDate());
        add(fields, "details.availability", "实习安排", details.availability());
        for (int i = 0; i < details.projects().size(); i++) {
            var project = details.projects().get(i);
            String path = "details.projects[" + i + "]";
            addAll(fields, path + ".contributions", project.name() + " · 项目贡献", project.contributions());
            addAll(fields, path + ".techStack", project.name() + " · 技术栈声明", project.techStack());
            add(fields, path + ".background", project.name() + " · 项目背景", project.background());
        }
        for (int i = 0; i < details.education().size(); i++) {
            var education = details.education().get(i);
            String path = "details.education[" + i + "]";
            add(fields, path + ".school", "学校", education.school());
            add(fields, path + ".major", "专业", education.major());
            add(fields, path + ".degree", "学历", education.degree());
        }
        return fields;
    }

    private static void addAll(List<Evidence> fields, String path, String label, List<String> texts) {
        if (texts != null) for (int i = 0; i < texts.size(); i++) add(fields, path + "[" + i + "]", label, texts.get(i));
    }
    private static void add(List<Evidence> fields, String path, String label, String text) {
        if (text != null && !text.isBlank()) fields.add(new Evidence(path, label, text, List.of()));
    }
}
