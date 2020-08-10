package org.corpus_tools.peppermodules.TCFModules.tests;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;

/**
 * This class fixes broken TCF documents where the <text> tag does not
 * match the sum of the <tc:token> tags.
 */

public class TestAndre {


    public static void main(String[] args){
        String root = "/home/andre/IdeaProjects/David/files/";
        File f = new File(root);
        processDirectory(f);
    }


    public static void processDirectory(File dir){
        try {
            File[] files = dir.listFiles();
             for(int i=0;i<files.length;i++){
                 if(files[i].isFile()){
                     processFile(files[i].getAbsolutePath());
                 } else {
                     processDirectory(files[i]);
                 }
             }
        } catch(Exception e){
            e.printStackTrace();
        }
    }

    public static void processFile(String filepath){
        try {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            File xml = new File(filepath);
            Document doc = builder.parse(xml);
            NodeList nodeList = doc.getElementsByTagName("tc:token");
            StringBuilder text = new StringBuilder();

            for (int itr = 0; itr < nodeList.getLength(); itr++) {
                Node node = nodeList.item(itr);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element eElement = (Element) node;
                    if(node.getTextContent().equalsIgnoreCase(".")){
                        text = new StringBuilder(text.substring(0,text.length()-1));
                    }
                    text.append(node.getTextContent());
                    text.append(" ");
                }
            }

            Node txt = doc.getElementsByTagName("text").item(0);
            txt.setTextContent(text.toString());
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File(filepath));
            transformer.transform(source, result);


        } catch(Exception e){
            e.printStackTrace();
        }
    }
}
