package com.familyfinance.notification;
import com.familyfinance.shared.ApiEnvelope;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/notifications") public class NotificationController {private final NotificationService service;NotificationController(NotificationService service){this.service=service;} @GetMapping ApiEnvelope<NotificationPage> list(Authentication a){return ApiEnvelope.data(service.list(a));} @PostMapping("/generate") ApiEnvelope<Integer> generate(Authentication a){return ApiEnvelope.data(service.generate(a));} @PostMapping("/{id}/read") ApiEnvelope<NotificationResponse> read(Authentication a,@PathVariable long id){return ApiEnvelope.data(service.read(a,id));} @PostMapping("/{id}/resolve") ApiEnvelope<NotificationResponse> resolve(Authentication a,@PathVariable long id){return ApiEnvelope.data(service.resolve(a,id));}}
