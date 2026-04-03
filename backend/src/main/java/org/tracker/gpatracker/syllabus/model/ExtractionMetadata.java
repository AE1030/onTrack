package org.tracker.gpatracker.syllabus.model;

public class ExtractionMetadata {
    private String model;
    private Double temperature;

    public ExtractionMetadata() {
    }

    public ExtractionMetadata(String model, Double temperature) {
        this.model = model;
        this.temperature = temperature;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }
}
