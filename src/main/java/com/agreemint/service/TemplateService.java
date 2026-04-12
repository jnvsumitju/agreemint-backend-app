package com.agreemint.service;

import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.CreateTemplateRequest;
import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.domain.Template;
import com.agreemint.repository.TemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TemplateService {

    private final TemplateRepository templateRepository;

    public TemplateService(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Transactional
    public TemplateResponse create(CreateTemplateRequest request) {
        Template t = new Template();
        t.setName(request.name());
        t.setCreatedBy(request.createdBy());
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
