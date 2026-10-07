package com.neocat.organization.infra.jdbc;

import com.neocat.organization.domain.membership.MembershipRepository;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Repository
public class MembershipRepositoryAdapter implements MembershipRepository {
    private final MembershipMapper mapper;

    public MembershipRepositoryAdapter(MembershipMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    public void add(long orgId, long accountId) { mapper.add(orgId, accountId); }

    @Override
    public void remove(long orgId, long accountId) { mapper.remove(orgId, accountId); }

    @Override
    public Set<Long> membersOf(long orgId) { return Set.copyOf(mapper.membersOf(orgId)); }

    @Override
    public Set<Long> orgsOf(long accountId) { return Set.copyOf(mapper.orgsOf(accountId)); }

    @Override
    public void removeAllOf(long accountId) { mapper.removeAllOf(accountId); }
}
