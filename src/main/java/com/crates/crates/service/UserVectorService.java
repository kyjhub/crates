package com.crates.crates.service;

import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.user.UserVector;
import com.crates.crates.repository.UserVectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserVectorService {

    private final UserVectorRepository userVectorRepository;

    public float[] getVector(Long userId)
    {
        return userVectorRepository.findById(userId)
                .map(UserVector::getUserVector)
                .orElseThrow(() -> new BusinessException("사용자 벡터가 존재하지 않습니다. userId: " + userId));
    }
}
