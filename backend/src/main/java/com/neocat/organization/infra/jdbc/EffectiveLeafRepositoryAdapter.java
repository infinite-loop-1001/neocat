package com.neocat.organization.infra.jdbc;

import com.neocat.organization.domain.membership.EffectiveLeafRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Repository
public class EffectiveLeafRepositoryAdapter implements EffectiveLeafRepository {
    private final EffectiveLeafMapper mapper;

    public EffectiveLeafRepositoryAdapter(EffectiveLeafMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    @Transactional
    public void replaceAll(long accountId, Set<Long> leafOrgIds) {
        mapper.deleteByAccount(accountId);
        for (Long orgId : leafOrgIds) {
            mapper.insert(accountId, orgId);
        }
    }
    @Override
    public Set<Long> leavesOf(long accountId) { return Set.copyOf(mapper.leavesOf(accountId)); }

    @Override
    public Set<Long> membersOf(long orgId) { return Set.copyOf(mapper.membersOf(orgId)); }

    @Override
    public void removeOrg(long orgId) { mapper.removeOrg(orgId); }
}
