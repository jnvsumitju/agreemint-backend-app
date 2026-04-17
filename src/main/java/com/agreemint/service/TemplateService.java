package com.agreemint.service;

import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.CreateTemplateRequest;
import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.domain.Template;
import com.agreemint.repository.TemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class TemplateService {

    private final TemplateRepository templateRepository;

    public TemplateService(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /**
     * Legacy overload retained for callers that can't supply ownership yet
     * (e.g. import/clone helpers). Produces a template with no owner/org —
     * {@link com.agreemint.security.OrgAuthorizationService} now rejects
     * access to such orphans rather than treating them as wide-open.
     *
     * @deprecated Use {@link #create(CreateTemplateRequest, UUID, UUID)} so the
     *     template is bound to the creator's org + user.
     */
    @Deprecated
    @Transactional
    public TemplateResponse create(CreateTemplateRequest request) {
        Template t = new Template();
        t.setName(request.name());
        t.setCreatedBy(request.createdBy());
        templateRepository.save(t);
        return toResponse(t);
    }

    /**
     * Create a new template owned by {@code ownerId} and scoped to {@code orgId}.
     * Both are required — without them the authorization layer has nothing to
     * enforce access against.
     */
    @Transactional
    public TemplateResponse create(CreateTemplateRequest request, UUID orgId, UUID ownerId) {
        if (orgId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No organization context");
        }
        Template t = new Template();
        t.setName(request.name());
        t.setCreatedBy(request.createdBy());
        t.setOrgId(orgId);
        t.setOwnerId(ownerId);
        templateRepository.save(t);
        return toResponse(t);
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> listAll() {
        return templateRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Template getById(UUID id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Template not found"));
    }

    @Transactional(readOnly = true)
    public TemplateResponse getResponse(UUID id) {
        return toResponse(getById(id));
    }

    private TemplateResponse toResponse(Template t) {
        return new TemplateResponse(t.getId(), t.getName(), t.getCreatedBy(), t.getCreatedAt());
    }
}
