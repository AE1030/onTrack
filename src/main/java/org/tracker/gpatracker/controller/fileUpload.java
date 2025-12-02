package org.tracker.gpatracker.controller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.tracker.gpatracker.service.GPAService;

import java.io.IOException;

@RestController("api/file")
public class fileUpload {

    @Autowired
    GPAService service;

    // 100 MB max for example **double check this number here****
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadPdf(@RequestParam("file") MultipartFile file) throws IOException {//spring expects a form field named file in the multipart/form-data request.
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("File is empty.");
        }
        //Enforce a pdf mime type, never trust the extension alone
        if (!file.getContentType().equalsIgnoreCase("application/pdf")) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body("Only PDF files are allowed.");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body("File is too large.");
        }

        // 4. OPTIONAL: scan for malware, validate PDF structure,


        // If all checks pass, proceed with processing the file


        Double result = service.processFile(file);
        return ResponseEntity.ok(String.valueOf(result)); //want to return the actual file




    }
}
