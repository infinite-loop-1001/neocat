package com.neocat.organization.domain.tree;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@org.springframework.modulith.NamedInterface("isOrganization")

public interface OrgNodeRepository {

    OrgNode create(String name, Long parentId);

    OrgNode save(OrgNode node);

    @org.springframework.lang.Nullable
    OrgNode findById(long id);

    List<OrgNode> findAll();

    List<OrgNode> childrenOf(long id);

    void delete(long id);
}
