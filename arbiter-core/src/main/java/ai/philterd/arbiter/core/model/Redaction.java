/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.arbiter.core.model;

public class Redaction {
    private String id;
    private String text;
    private int start;
    private int end;
    private String type;
    private int pageNumber;
    private float lowerLeftX;
    private float lowerLeftY;
    private float upperRightX;
    private float upperRightY;

    public Redaction() {
    }

    public Redaction(String id, String text, int start, int end, String type) {
        this.id = id;
        this.text = text;
        this.start = start;
        this.end = end;
        this.type = type;
    }

    public Redaction(String id, String text, int start, int end, String type, int pageNumber, float lowerLeftX, float lowerLeftY, float upperRightX, float upperRightY) {
        this.id = id;
        this.text = text;
        this.start = start;
        this.end = end;
        this.type = type;
        this.pageNumber = pageNumber;
        this.lowerLeftX = lowerLeftX;
        this.lowerLeftY = lowerLeftY;
        this.upperRightX = upperRightX;
        this.upperRightY = upperRightY;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    public int getEnd() {
        return end;
    }

    public void setEnd(int end) {
        this.end = end;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public float getLowerLeftX() {
        return lowerLeftX;
    }

    public void setLowerLeftX(float lowerLeftX) {
        this.lowerLeftX = lowerLeftX;
    }

    public float getLowerLeftY() {
        return lowerLeftY;
    }

    public void setLowerLeftY(float lowerLeftY) {
        this.lowerLeftY = lowerLeftY;
    }

    public float getUpperRightX() {
        return upperRightX;
    }

    public void setUpperRightX(float upperRightX) {
        this.upperRightX = upperRightX;
    }

    public float getUpperRightY() {
        return upperRightY;
    }

    public void setUpperRightY(float upperRightY) {
        this.upperRightY = upperRightY;
    }
}
