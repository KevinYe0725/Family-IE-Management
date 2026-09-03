package com.familyfinance.notification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component public class NotificationSchedule {private final NotificationService service;NotificationSchedule(NotificationService service){this.service=service;} @Scheduled(cron="0 20 0 * * *",zone="Asia/Shanghai") public void generateDaily(){service.generateAll();}}
