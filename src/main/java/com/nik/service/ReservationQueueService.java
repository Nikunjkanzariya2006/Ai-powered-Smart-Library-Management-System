package com.nik.service;

public interface ReservationQueueService {

    int processNextReservation(Long bookId);
}
