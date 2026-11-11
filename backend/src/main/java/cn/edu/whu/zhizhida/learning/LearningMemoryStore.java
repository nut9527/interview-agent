package cn.edu.whu.zhizhida.learning;

import cn.edu.whu.zhizhida.common.Sha256;
import cn.edu.whu.zhizhida.learning.api.*;
import cn.edu.whu.zhizhida.learning.api.LearningMemory.*;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** 持久化观察事件、用户修订和画像快照；通过稳定观察标识与删除标记保持重放幂等。 */
@Repository
public class LearningMemoryStore implements ObservationJournal {
    record Event(long id,long owner,String session,String version) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public LearningMemoryStore(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void record(long owner,String session,String version) {
        jdbc.update("INSERT INTO learning_observation_events(owner_id,source_session_id,source_version) VALUES(?,?,?) "
                + "ON DUPLICATE KEY UPDATE id=id",owner,session,version);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void sourceRemoved(long owner,String session) {
        preferences(owner);
        touch(owner);
        jdbc.update("DELETE FROM learning_profile_snapshots WHERE owner_id=?",owner);
    }
    List<Event> pending() {
        return jdbc.query("SELECT * FROM learning_observation_events WHERE status='PENDING' ORDER BY id LIMIT 20",
                (r,n)->new Event(r.getLong("id"),r.getLong("owner_id"),r.getString("source_session_id"),r.getString("source_version")));
    }
    boolean lockEvent(long id) {
        return jdbc.query("SELECT id FROM learning_observation_events WHERE id=? AND status='PENDING' FOR UPDATE",
                (r,n)->r.getLong(1),id).size()==1;
    }
    void processed(long id) { jdbc.update("UPDATE learning_observation_events SET status='DONE',processed_at=CURRENT_TIMESTAMP(6) WHERE id=?",id); }
    void failed(long id) { jdbc.update("UPDATE learning_observation_events SET attempts=attempts+1,status=IF(attempts>=3,'FAILED','PENDING') WHERE id=? AND status='PENDING'",id); }
    Preferences preferences(long owner) {
        jdbc.update("INSERT INTO learning_preferences(owner_id) VALUES(?) ON DUPLICATE KEY UPDATE owner_id=owner_id",owner);
        return jdbc.queryForObject("SELECT * FROM learning_preferences WHERE owner_id=? FOR UPDATE",
                (r,n)->new Preferences(r.getBoolean("personalization_enabled"),r.getLong("revision")),owner);
    }
    void touch(long owner) { jdbc.update("UPDATE learning_preferences SET revision=revision+1 WHERE owner_id=?",owner); }
    void preference(long owner,boolean enabled) {
        jdbc.update("UPDATE learning_preferences SET personalization_enabled=?,revision=revision+1 WHERE owner_id=?",enabled,owner);
    }
    boolean personalizationEnabled(long owner) {
        return jdbc.query("SELECT personalization_enabled FROM learning_preferences WHERE owner_id=?",
                (r,n)->r.getBoolean(1),owner).stream().findFirst().orElse(false);
    }
    List<Observation> practiceCandidates(long owner,List<String> scope,String mode,java.time.Instant since,String excluded) {
        var args=new ArrayList<Object>(); args.add(owner); args.add(mode); args.add(java.sql.Timestamp.from(since));
        args.add(excluded); args.addAll(scope);
        return jdbc.query("""
                WITH session_samples AS (
                    SELECT o.*, ROW_NUMBER() OVER (
                        PARTITION BY competency_id,dimension,rubric_version,source_session_id
                        ORDER BY source_round DESC,created_at DESC,id DESC
                    ) AS session_rank
                    FROM learning_observations o
                    WHERE owner_id=? AND execution_mode=? AND created_at>=? AND source_session_id<>?
                        AND status='ACTIVE' AND rubric_version IN ('qualitative-v1','behavioral-six-v1')
                        AND competency_id IN (%s)
                ), history_samples AS (
                    SELECT s.*, ROW_NUMBER() OVER (
                        PARTITION BY competency_id,dimension,rubric_version
                        ORDER BY created_at DESC,id DESC
                    ) AS history_rank
                    FROM session_samples s WHERE session_rank=1
                )
                SELECT * FROM history_samples WHERE history_rank<=4 ORDER BY created_at DESC,id DESC
                """.formatted(String.join(",",Collections.nCopies(scope.size(),"?"))),mapper,args.toArray());
    }
    private final RowMapper<Observation> mapper=(r,n)->map(r);
    private Observation map(java.sql.ResultSet r) throws java.sql.SQLException { return new Observation(r.getString("id"),r.getString("source_session_id"),
            r.getString("source_version"),r.getInt("source_round"),r.getString("competency_id"),r.getString("dimension"),
            r.getString("rubric_version"),r.getString("execution_mode"),r.getString("status"),r.getLong("revision"),
            r.getString("payload_json")==null?null:json.readValue(r.getString("payload_json"),Fact.class),
            r.getString("correction_json")==null?null:json.readValue(r.getString("correction_json"),String.class),r.getTimestamp("created_at").toInstant()); }
    List<Observation> recent(long owner) {
        return jdbc.query("SELECT * FROM learning_observations WHERE owner_id=? AND status<>'DELETED' ORDER BY created_at DESC,id DESC LIMIT 1000",mapper,owner);
    }
    List<Observation> recentCurrent(long owner) {
        return jdbc.query("SELECT * FROM learning_observations WHERE owner_id=? AND status<>'DELETED' ORDER BY created_at DESC,id DESC LIMIT 1000 FOR UPDATE",mapper,owner);
    }
    List<Observation> page(long owner,int offset,int size) {
        return jdbc.query("SELECT * FROM learning_observations WHERE owner_id=? AND status<>'DELETED' ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",mapper,owner,size,offset);
    }
    List<Observation> export(long owner,String after) {
        return jdbc.query("SELECT * FROM learning_observations WHERE owner_id=? AND status<>'DELETED' AND id>? ORDER BY id LIMIT 101",mapper,owner,after);
    }
    long count(long owner) { return jdbc.queryForObject("SELECT COUNT(*) FROM learning_observations WHERE owner_id=? AND status<>'DELETED' FOR UPDATE",Long.class,owner); }
    Sync sync(long owner) {
        return new Sync(jdbc.queryForObject("SELECT COUNT(*) FROM learning_observation_events WHERE owner_id=? AND status='PENDING'",Long.class,owner),
                jdbc.queryForObject("SELECT COUNT(*) FROM learning_observation_events WHERE owner_id=? AND status='FAILED'",Long.class,owner));
    }
    Observation get(long owner,String id) {
        return jdbc.query("SELECT * FROM learning_observations WHERE owner_id=? AND id=?",mapper,owner,id).stream()
                .findFirst().orElseThrow(()->new ResourceNotFoundException("观察不存在"));
    }
    Observation lockObservation(long owner,String id) {
        return current(owner,id).stream()
                .findFirst().orElseThrow(()->new ResourceNotFoundException("观察不存在"));
    }
    List<Observation> current(long owner,String id) {
        return jdbc.query("SELECT * FROM learning_observations WHERE owner_id=? AND id=? FOR UPDATE",mapper,owner,id);
    }
    List<Observation> current(long owner,List<String> ids) {
        if(ids.isEmpty()) return List.of();
        var args=new ArrayList<Object>(); args.add(owner); args.addAll(ids);
        return jdbc.query("SELECT * FROM learning_observations WHERE owner_id=? AND id IN ("
                +String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id FOR UPDATE",mapper,args.toArray());
    }
    void add(long owner,PracticeEvidenceSource.SourceRef source,Fact f) {
        String id=Sha256.hex(owner+"/"+source.sessionId()+"/"+f.round()+"/"+f.competencyId()+"/"+f.dimension()+"/"+f.rubricVersion());
        var existing=current(owner,id);
        if(!existing.isEmpty()) {
            var previous=existing.getFirst();
            // 重新评价更新同一事实；用户更正、删除和来源撤销均不可被重放恢复。
            if(previous.status().equals("ACTIVE") && !previous.sourceVersion().equals(source.version())) {
                jdbc.update("UPDATE learning_observations SET source_version=?,execution_mode=?,payload_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE owner_id=? AND id=?",
                        source.version(),f.executionMode(),json.writeValueAsString(f),owner,id);
                touch(owner);
            }
            return;
        }
        jdbc.update("INSERT INTO learning_observations(id,owner_id,source_session_id,source_version,source_round,competency_id,dimension,rubric_version,execution_mode,payload_json) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?)",id,owner,source.sessionId(),source.version(),f.round(),f.competencyId(),f.dimension(),f.rubricVersion(),f.executionMode(),json.writeValueAsString(f));
        touch(owner);
    }
    // 来源版本变化或消失时撤销观察并清空正文和更正内容，同时使画像快照失效。
    void withdrawChanged(long owner,String session,String currentVersion) {
        int changed=jdbc.update("UPDATE learning_observations SET status='REVOKED',payload_json=NULL,correction_json=NULL,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE owner_id=? AND source_session_id=? AND status IN ('ACTIVE','CORRECTED') AND (? IS NULL OR source_version<>?)",owner,session,currentVersion,currentVersion);
        if(changed>0) {
            touch(owner);
            jdbc.update("DELETE FROM learning_profile_snapshots WHERE owner_id=?",owner);
        }
        jdbc.update("DELETE c FROM learning_corrections c JOIN learning_observations o ON c.observation_id=o.id WHERE o.owner_id=? AND o.source_session_id=? AND o.status='REVOKED'",owner,session);
    }
    void change(long owner,String id,String status,String correction) {
        if(correction!=null) jdbc.update("INSERT INTO learning_corrections(observation_id,source_revision,owner_id,text_json) SELECT id,revision,owner_id,? FROM learning_observations WHERE owner_id=? AND id=?",json.writeValueAsString(correction),owner,id);
        else jdbc.update("DELETE FROM learning_corrections WHERE owner_id=? AND observation_id=?",owner,id);
        jdbc.update("UPDATE learning_observations SET status=?,payload_json=NULL,correction_json=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(6) WHERE owner_id=? AND id=?",
                status,correction==null?null:json.writeValueAsString(correction),owner,id);
        touch(owner);
    }
    void snapshot(long owner,Profile profile) {
        jdbc.update("INSERT INTO learning_profile_snapshots(owner_id,revision,reducer_version,profile_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE "
                + "revision=VALUES(revision),reducer_version=VALUES(reducer_version),profile_json=VALUES(profile_json),updated_at=CURRENT_TIMESTAMP(6)",owner,profile.revision(),profile.reducerVersion(),json.writeValueAsString(profile));
    }
}
