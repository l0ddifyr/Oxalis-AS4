package no.difi.oxalis.as4.util;

import no.difi.oxalis.as4.lang.OxalisAs4Exception;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.util.Date;
import java.util.GregorianCalendar;

public class XMLUtil {

    public static XMLGregorianCalendar dateToXMLGeorgianCalendar(Date date) throws OxalisAs4Exception{
        try {
            GregorianCalendar gc = new GregorianCalendar();
            gc.setTime(date);

            return DatatypeFactory.newInstance().newXMLGregorianCalendar(gc);
        }catch (DatatypeConfigurationException e){
            throw new OxalisAs4Exception("Unable to convert timestamp to XML", e);
        }
    }
}
