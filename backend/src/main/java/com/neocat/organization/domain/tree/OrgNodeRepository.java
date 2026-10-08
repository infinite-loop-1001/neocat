package com.neocat.organization.domain.tree;

import java.util.List;

import org.springframework.lang.Nullable;
import org.springframework.modulith.NamedInterface;

@NamedInterface("isOrganization")
public interface OrgNodeRepository {

    OrgNode create(String name, Long parentId);

    OrgNode save(OrgNode node);

    @Nullable
    OrgNode findById(long id);

    List<OrgNode> findAll();

    List<OrgNode> childrenOf(long id);

    void delete(long id);
}
