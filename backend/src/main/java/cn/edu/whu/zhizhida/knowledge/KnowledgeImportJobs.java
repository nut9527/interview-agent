package cn.edu.whu.zhizhida.knowledge;
import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.*;
import cn.edu.whu.zhizhida.knowledge.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

/** 受理本地解析或共享文件任务；确认入库统一检查账号归属、用户确认与资料容量。 */
@Service
public class KnowledgeImportJobs {
    private final KnowledgeImportRepository repository;
    private final KnowledgeImportExecutor executor;
    private final KnowledgeService documents;
    private final CurrentUserProvider users;
    private final ObjectMapper json;

    public KnowledgeImportJobs(KnowledgeImportRepository repository, KnowledgeImportExecutor executor,
                               KnowledgeService documents, CurrentUserProvider users, ObjectMapper json) {
        this.repository = repository;
        this.executor = executor;
        this.documents = documents;
        this.users = users;
        this.json = json;
    }
    public KnowledgeImportJob submit(MultipartFile file) {
        return find(executor.submit(users.requireUserId(), file));
    }
    public List<KnowledgeImportJob> list() { return repository.list(users.requireUserId()); }
    private KnowledgeImportRepository.Stored owned(String id,boolean lock) {
        var row=repository.find(users.requireUserId(),id,lock).orElseThrow(()->new ResourceNotFoundException("导入任务不存在或已过期，请重新上传。"));
        if(row.job().expiresAt().isBefore(Instant.now())) throw new ResourceNotFoundException("导入任务已过期，请重新上传。");
        return row;
    }
    public KnowledgeImportJob find(String id) { return owned(id,false).job(); }
    public KnowledgeImport result(String id) {
        var row=owned(id,false);
        if(!row.job().status().equals("READY")) throw new BusinessException(ErrorCode.CONFLICT,"解析尚未完成或任务已结束。");
        return json.readValue(row.draft(),KnowledgeImport.class);
    }
    @Transactional public KnowledgeDocument confirm(String id,KnowledgeImport.Confirm request) {
        // 与创建和账号注销保持账号 → 导入任务的锁顺序，避免相互等待。
        documents.lockOwner();
        // 锁定导入任务后再确认，重复请求返回已创建资料，避免生成重复文档。
        var row=owned(id,true);
        if(row.job().status().equals("IMPORTED")) return documents.find(row.job().documentId());
        if(!row.job().status().equals("READY")) throw new BusinessException(ErrorCode.CONFLICT,"仅能确认已完成的解析任务。");
        var doc=documents.confirmImport(new KnowledgeImport.Confirm(request.title(),row.job().sourceName(),request.content(),request.confirmed()));
        repository.imported(users.requireUserId(),id,doc.id());return doc;
    }
    public void cancel(String id) {
        owned(id,false);Long user=users.requireUserId();repository.stop(user,id,"CANCELED","本次导入已取消，未保存资料。");
        executor.cancel(id);
    }
}
