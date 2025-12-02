package org.tracker.gpatracker.service.GPAUtils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

public class TextConverter {
    public static String[] convertPDFtoTxt(MultipartFile file) throws IOException {
        PDDocument pddDoc = Loader.loadPDF(file.getInputStream());
        PDFTextStripper reader = new PDFTextStripper();
        String pageText = reader.getText(pddDoc);
        pddDoc.close();
        return pageText.split("\n");
    }
}