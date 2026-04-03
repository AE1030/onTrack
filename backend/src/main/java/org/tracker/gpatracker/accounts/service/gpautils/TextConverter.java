package org.tracker.gpatracker.accounts.service.gpautils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public class TextConverter {

    private TextConverter() {}

    public static String[] convertPDFtoTxt(MultipartFile file) throws IOException {
        PDDocument pddDoc = Loader.loadPDF(file.getBytes());
        PDFTextStripper reader = new PDFTextStripper();
        String pageText = reader.getText(pddDoc);
        pddDoc.close();
        return pageText.split("\n");
    }
}