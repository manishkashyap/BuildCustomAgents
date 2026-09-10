package com.manish.customagents.credential.repository;

import com.manish.customagents.credential.entity.CredentialBindingEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialBindingEventRepository
        extends JpaRepository<CredentialBindingEventEntity, Long> {
    List<CredentialBindingEventEntity> findTop200ByLicenseCodeOrderByOccurredAtDesc(String licenseCode);
}
