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
    public void add(long orgId, String userAccount) { mapper.add(orgId, userAccount); }

    @Override
    public void remove(long orgId, String userAccount) { mapper.remove(orgId, userAccount); }

    @Override
    public Set<String> membersOf(long orgId) { return Set.copyOf(mapper.membersOf(orgId)); }

    @Override
    public Set<Long> orgsOf(String userAccount) { return Set.copyOf(mapper.orgsOf(userAccount)); }

    @Override
    public void removeAllOf(String userAccount) { mapper.removeAllOf(userAccount); }
}
