package com.shakenokirimi12.uoa_app.data.models;

import java.util.List;

public class GakushokuMenuItem {
    private String date;
    private String lunch;
    private String fish;
    private String salad;
    private String dinner;

    public GakushokuMenuItem() {}

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getLunch() { return lunch; }
    public void setLunch(String lunch) { this.lunch = lunch; }

    public String getFish() { return fish; }
    public void setFish(String fish) { this.fish = fish; }

    public String getSalad() { return salad; }
    public void setSalad(String salad) { this.salad = salad; }

    public String getDinner() { return dinner; }
    public void setDinner(String dinner) { this.dinner = dinner; }

    public String getMenuSummary() {
        StringBuilder sb = new StringBuilder();
        if (lunch != null && !lunch.isEmpty()) sb.append("ランチ: ").append(lunch);
        if (fish != null && !fish.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("フィッシュ: ").append(fish);
        }
        if (salad != null && !salad.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("サラダ: ").append(salad);
        }
        if (dinner != null && !dinner.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("ディナー: ").append(dinner);
        }
        return sb.toString();
    }
}
