package com.neocat.organization.infra.jdbc;

import com.neocat.organization.domain.tree.OrgNode;
import com.neocat.organization.domain.tree.OrgNodeRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * 组织树仓储的 MyBatis 适配器（表 {@code nc_org_node}）。
 *
 * <p>一期不支持移动节点，因此 {@code save} 只更新名称。
 */
@Repository
public class OrgNodeRepositoryAdapter implements OrgNodeRepository {

    private final OrgNodeMapper mapper;

    public OrgNodeRepositoryAdapter(OrgNodeMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    public OrgNode create(String name, Long parentId) {
        OrgNodeRow row = new OrgNodeRow();
        row.setName(name);
        row.setParentId(parentId);
        mapper.insert(row);
        return toDomain(row);
    }
    @Override
    public OrgNode save(OrgNode node) {
        OrgNodeRow row = new OrgNodeRow();
        row.setId(node.getId());
        row.setName(node.getName());
        row.setParentId(node.getParentId());
        mapper.update(row);
        return node;
    }
    @Override
    public OrgNode findById(long id) {
        var row = mapper.selectById(id);
        return Objects.isNull(row) ? null : toDomain(row);
    }
    @Override
    public List<OrgNode> findAll() {
        return mapper.selectAll().stream().map(OrgNodeRepositoryAdapter::toDomain).toList();
    }
    @Override
    public List<OrgNode> childrenOf(long id) {
        return mapper.selectChildren(id).stream().map(OrgNodeRepositoryAdapter::toDomain).toList();
    }
    @Override
    public void delete(long id) {
        mapper.delete(id);
    }
    /** 数据库行（列名与 nc_org_node 一致）。 */
    public static class OrgNodeRow {
        private Long id;

        private String name;

        private Long parentId;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(Long parentId) {
            this.parentId = parentId;
        }
    }
    private static OrgNode toDomain(OrgNodeRow row) {
        return new OrgNode(row.getId(), row.getName(), row.getParentId());
    }
}
