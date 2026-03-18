package com.linkwork.service;

import org.springframework.stereotype.Service;

/**
 * Pluggable admin check. Override this bean to integrate with your own RBAC.
 */
@Service
public class AdminAccessService {

    public boolean isAdmin(String userId) {
        return false;
    }
}
