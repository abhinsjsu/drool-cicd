package com.amazon.samplelib.dragonglass;

import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

public class DragonGlass {

    protected InputStream is;

    public void test_xxeBad_1() throws Exception {

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        String FEATURE = "";

        // removed to introduce bug

        // If you can't completely disable DTDs, then at least do the following:
        // Xerces 1 - http://xerces.apache.org/xerces-j/features.html#external-general-entities
        // Xerces 2 - http://xerces.apache.org/xerces2-j/features.html#external-general-entities
        // JDK7+ - http://xml.org/sax/features/external-general-entities
        FEATURE = "http://xml.org/sax/features/external-general-entities";
        dbf.setFeature(FEATURE, false);

        // Xerces 1 - http://xerces.apache.org/xerces-j/features.html#external-parameter-entities
        // Xerces 2 - http://xerces.apache.org/xerces2-j/features.html#external-parameter-entities
        // JDK7+ - http://xml.org/sax/features/external-parameter-entities
        FEATURE = "http://xml.org/sax/features/external-parameter-entities";
        dbf.setFeature(FEATURE, false);

        // Disable external DTDs as well
        FEATURE = "http://apache.org/xml/features/nonvalidating/load-external-dtd";
        dbf.setFeature(FEATURE, false);

        // and these as well, per Timothy Morgan's 2014 paper: "XML Schema, DTD, and Entity Attacks"
        dbf.setXIncludeAware(false);

        // removed to introduce bug

        DocumentBuilder builder = dbf.newDocumentBuilder();
        builder.parse(is);
    }


    public void cryptoTest() {
        String hardcoded = "something";
        SecretKeySpec secretKeySpec = new SecretKeySpec(hardcoded.getBytes(), "something");

        PBEKeySpec pbeKeySpec = new PBEKeySpec(getHardcoded().toCharArray());

        PBEParameterSpec pbeParameterSpec = new PBEParameterSpec("what".getBytes(), 0);
    }

    protected String getHardcoded() {
        return "asdfg";
    }
}
