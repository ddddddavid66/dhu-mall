package com.yueji.mall.platform.controller;

import com.yueji.mall.common.api.*;
import com.yueji.mall.common.web.TraceIdFilter;
import com.yueji.mall.platform.model.FileModels.*;
import com.yueji.mall.platform.service.FileService;
import com.yueji.mall.identity.model.Actor;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Set;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@Hidden @RestController @RequestMapping("/api/v1/files")
public class FileController {
    private final FileService service;
    public FileController(FileService service) {this.service=service;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Result<FileView> upload(@AuthenticationPrincipal Actor actor,@RequestParam MultipartFile file,@RequestParam Purpose purpose,MultipartHttpServletRequest request) {
        if(!request.getFileMap().keySet().equals(Set.of("file")) || !request.getParameterMap().keySet().equals(Set.of("purpose")) || request.getFiles("file").size()!=1 || request.getParameterValues("purpose").length!=1) throw new BusinessException(ErrorCode.PARAM_INVALID);
        return Result.ok(service.upload(actor,file,purpose),TraceIdFilter.current(request));
    }
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Actor actor,@PathVariable String id) {
        var file=service.content(actor,id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","inline; filename=\"image.png\"").header("Cache-Control",file.publicFile()?"public, max-age=300":"private, no-store")
                .body(file.bytes());
    }
}
