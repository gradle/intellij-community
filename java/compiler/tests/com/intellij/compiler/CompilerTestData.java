/**
 * created at Jan 22, 2002
 * @author Jeka
 */
package com.intellij.compiler;

import com.intellij.openapi.util.JDOMExternalizable;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import org.jdom.Element;

import java.util.*;

public class CompilerTestData implements JDOMExternalizable {
  private final Set<String> myPathsToDelete = new HashSet<String>();
  private String[] myDeletedByMake;
  private String[] myToRecompile;

  public void readExternal(Element element) throws InvalidDataException {

    // read paths to be deleted
    myPathsToDelete.clear();
    for (Object o3 : element.getChildren("delete")) {
      Element elem = (Element)o3;
      for (Object o : elem.getChildren()) {
        Element pathElement = (Element)o;
        myPathsToDelete.add(pathElement.getAttributeValue("path"));
      }
    }

    // read paths that are expected to be deleted
    List<String> data = new ArrayList<String>();
    for (Object o2 : element.getChildren("deleted_by_make")) {
      Element elem = (Element)o2;
      for (Object o : elem.getChildren()) {
        Element pathElement = (Element)o;
        data.add(pathElement.getAttributeValue("path"));
      }
    }
    myDeletedByMake = data.toArray(new String[data.size()]);

    // read paths that are expected to be found by dependencies
    data.clear();
    for (Object o1 : element.getChildren("recompile")) {
      Element elem = (Element)o1;
      for (Object o : elem.getChildren()) {
        Element pathElement = (Element)o;
        data.add(pathElement.getAttributeValue("path"));
      }
    }
    myToRecompile = data.toArray(new String[data.size()]);
  }

  public void writeExternal(Element element) throws WriteExternalException {
    throw new WriteExternalException("Save not supported");
  }

  public String[] getDeletedByMake() {
    return myDeletedByMake;
  }

  public boolean shouldDeletePath(String path) {
    return myPathsToDelete.contains(path);
  }

  public String[] getToRecompile() {
    return myToRecompile;
  }
}
