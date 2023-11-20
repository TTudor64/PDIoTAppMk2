package com.specknet.pdiotapp.utils;
import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class CsvReader {
    private Context context;

    public CsvReader(Context context) {
        this.context = context;
    }

    public ArrayList<float[]> readCsv(String fileName) {
        ArrayList<float[]> resultList = new ArrayList<>();
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(
                    new InputStreamReader(context.getAssets().open(fileName)));

            String line;
            while ((line = reader.readLine()) != null) {
                String[] stringValues = line.split(",");
                float[] floatValues = new float[stringValues.length];
                for (int i = 0; i < stringValues.length; i++) {
                    floatValues[i] = Float.parseFloat(stringValues[i]);
                }
                resultList.add(floatValues);
            }
        } catch (IOException e) {
            // Handle exceptions
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // Handle exceptions
                    e.printStackTrace();
                }
            }
        }
        return resultList;
    }
}
