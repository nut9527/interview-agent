package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 仅回填派生元数据，保留原有编号、来源文本与收藏关系。 */
public class V17__backfill_job_list_fields extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        long after = Long.MIN_VALUE;
        while (true) {
            int count = 0;
            try (var read = context.getConnection().prepareStatement("SELECT id, valid_through, description, salary FROM job_postings WHERE id > ? ORDER BY id LIMIT 200");
                 var write = context.getConnection().prepareStatement("UPDATE job_postings SET closing_date=?, closed=?, salary_value=? WHERE id=?")) {
                read.setLong(1, after);
                try (var rows = read.executeQuery()) {
                    while (rows.next()) {
                        after = rows.getLong("id");
                        var fields = JobListingFields.of(rows.getString("valid_through"), rows.getString("description"), rows.getString("salary"));
                        write.setObject(1, fields.closingDate()); write.setBoolean(2, fields.closed());
                        write.setObject(3, fields.salaryValue()); write.setLong(4, after); write.addBatch(); count++;
                    }
                }
                write.executeBatch();
            }
            if (count < 200) return;
        }
    }
}
