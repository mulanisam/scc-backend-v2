package com.app.service;

import com.app.dto.ReportRequestDTO;
import com.app.dto.ReportResponseDTO;

public interface ReportService {

	ReportResponseDTO generateReport(ReportRequestDTO request);

}
