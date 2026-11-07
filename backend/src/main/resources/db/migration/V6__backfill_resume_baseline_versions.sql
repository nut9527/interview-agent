INSERT INTO resume_versions (
    resume_id,
    user_id,
    version_number,
    snapshot_json,
    change_type,
    change_note
)
SELECT
    r.id,
    r.user_id,
    1,
    JSON_OBJECT(
        'id', r.id,
        'ownerName', r.owner_name,
        'targetRole', r.target_role,
        'summary', r.summary,
        'skills', JSON_EXTRACT(r.skills_json, '$'),
        'educationExperiences', JSON_EXTRACT(r.education_json, '$'),
        'projectExperiences', JSON_EXTRACT(r.projects_json, '$'),
        'internshipExperiences', JSON_EXTRACT(r.internships_json, '$'),
        'certificates', JSON_EXTRACT(r.certificates_json, '$'),
        'templateKey', r.template_key
    ),
    'BASELINE',
    '版本管理启用时建立基线'
FROM resumes r
WHERE NOT EXISTS (
    SELECT 1
    FROM resume_versions rv
    WHERE rv.resume_id = r.id
      AND rv.user_id = r.user_id
);
