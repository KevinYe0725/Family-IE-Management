package com.familyfinance.notification;
import java.util.List;
public record NotificationPage(List<NotificationResponse> items,long unreadCount) {}
