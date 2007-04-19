package com.intellij.xml.impl.dtd;

import com.intellij.javaee.ExternalResourceManager;
import com.intellij.openapi.util.SimpleFieldCache;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.filters.ClassFilter;
import com.intellij.psi.meta.PsiMetaDataBase;
import com.intellij.psi.meta.PsiWritableMetaData;
import com.intellij.psi.scope.ElementClassHint;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.scope.processor.FilterElementProcessor;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.util.IncorrectOperationException;
import com.intellij.xml.XmlAttributeDescriptor;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.XmlNSDescriptor;
import com.intellij.xml.util.XmlNSDescriptorSequence;
import com.intellij.xml.util.XmlUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * @author Mike
 */
public class XmlElementDescriptorImpl extends BaseXmlElementDescriptorImpl implements PsiWritableMetaData {
  protected XmlElementDecl myElementDecl;
  private String myName;
  private volatile XmlAttlistDecl[] myAttlistDecl;
  private static Class[] ourParentClassesToScanAttributes = new Class[] { XmlMarkupDecl.class, XmlDocument.class };

  public XmlElementDescriptorImpl(XmlElementDecl elementDecl) {
    init(elementDecl);
  }

  public XmlElementDescriptorImpl() {}

  private static final SimpleFieldCache<XmlAttlistDecl[],XmlElementDescriptorImpl> myAttlistDeclCache = new SimpleFieldCache<XmlAttlistDecl[], XmlElementDescriptorImpl>() {
    protected final XmlAttlistDecl[] compute(final XmlElementDescriptorImpl xmlElementDescriptor) {
      return xmlElementDescriptor.doCollectAttlistDecls();
    }

    protected final XmlAttlistDecl[] getValue(final XmlElementDescriptorImpl xmlElementDescriptor) {
      return xmlElementDescriptor.myAttlistDecl;
    }

    protected final void putValue(final XmlAttlistDecl[] xmlAttlistDecls, final XmlElementDescriptorImpl xmlElementDescriptor) {
      xmlElementDescriptor.myAttlistDecl = xmlAttlistDecls;
    }
  };

  public PsiElement getDeclaration(){
    return myElementDecl;
  }

  public boolean processDeclarations(PsiElement context, PsiScopeProcessor processor, PsiSubstitutor substitutor, PsiElement lastElement, PsiElement place){
    final ElementClassHint hint = processor.getHint(ElementClassHint.class);
    final XmlTag tag = (XmlTag)context;
    final PsiMetaDataBase meta = tag.getMetaData();
    if (meta == null) {
      return true;
    }
    if(hint == null || hint.shouldProcess(XmlAttributeDecl.class)){
      final XmlAttlistDecl[] decls = getAttlistDecls();
      for (XmlAttlistDecl decl : decls) {
        if (decl.getNameElement() != null && decl.getNameElement().getText().equals(meta.getName())) {
          final XmlAttributeDecl[] attributes = decl.getAttributeDecls();
          for (XmlAttributeDecl attribute : attributes) {
            if (!processor.execute(attribute, substitutor)) return false;
          }
        }
      }
    }
    if(hint == null || hint.shouldProcess(XmlElementDecl.class)){
      final XmlElementDescriptor[] decls = getElementsDescriptors(tag);
      for (final XmlElementDescriptor decl : decls) {
        if (!processor.execute(decl.getDeclaration(), substitutor)) return false;
      }
    }

    return true;
  }

  public String getName(PsiElement context){
    return getName();
  }

  public String getName() {
    if (myName!=null) return myName;
    return myName = myElementDecl.getNameElement().getText();
  }

  public void init(PsiElement element){
    myElementDecl = (XmlElementDecl) element;
  }

  public Object[] getDependences(){
    return new Object[]{myElementDecl, ExternalResourceManager.getInstance()};
  }

  public XmlNSDescriptor getNSDescriptor() {
    return getNsDescriptorFrom(myElementDecl);
  }

  private static XmlNSDescriptor getNsDescriptorFrom(final PsiElement elementDecl) {
    final PsiFile file = elementDecl.getContainingFile();
    if(!(file instanceof XmlFile)) return null;
    final XmlDocument document = ((XmlFile)file).getDocument();
    XmlNSDescriptor descriptor = (XmlNSDescriptor) document.getMetaData();
    if(descriptor == null) descriptor = document.getDefaultNSDescriptor(XmlUtil.EMPTY_URI, false);
    return descriptor;
  }

  // Read-only action
  protected final XmlElementDescriptor[] doCollectXmlDescriptors(final XmlTag context) {
    final List<XmlElementDescriptor> result = new ArrayList<XmlElementDescriptor>();
    final XmlElementContentSpec contentSpecElement = myElementDecl.getContentSpecElement();
    final XmlNSDescriptor nsDescriptor = getNSDescriptor();
    final XmlNSDescriptor NSDescriptor = nsDescriptor != null? nsDescriptor:getNsDescriptorFrom(context);

    contentSpecElement.processElements(new PsiElementProcessor(){
      public boolean execute(PsiElement child){
        if (child instanceof XmlToken) {
          final XmlToken token = (XmlToken)child;

          if (token.getTokenType() == XmlTokenType.XML_NAME) {
            final String text = child.getText();
            XmlElementDescriptor element = getElementDescriptor(text, NSDescriptor);

            if (element != null) {
              result.add(element);
            }
          }
          else if (token.getTokenType() == XmlTokenType.XML_CONTENT_ANY) {
            if (NSDescriptor instanceof XmlNSDescriptorImpl) {
              result.addAll(Arrays.asList(((XmlNSDescriptorImpl) NSDescriptor).getElements()));
            } else if (NSDescriptor instanceof XmlNSDescriptorSequence) {

              for (XmlNSDescriptor xmlNSDescriptor : ((XmlNSDescriptorSequence)NSDescriptor).getSequence()) {
                if (xmlNSDescriptor instanceof XmlNSDescriptorImpl) {
                  result.addAll(Arrays.asList(((XmlNSDescriptorImpl) xmlNSDescriptor).getElements()));
                }
              }
            }
          }
        }
        return true;
      }
    }, getDeclaration());

    return result.toArray(new XmlElementDescriptor[result.size()]);
  }

  private static XmlElementDescriptor getElementDescriptor(final String text, final XmlNSDescriptor NSDescriptor) {
    XmlElementDescriptor element = null;
    if (NSDescriptor instanceof XmlNSDescriptorImpl) {
      element = ((XmlNSDescriptorImpl)NSDescriptor).getElementDescriptor(text);
    }
    else if (NSDescriptor instanceof XmlNSDescriptorSequence) {
      final List<XmlNSDescriptor> sequence = ((XmlNSDescriptorSequence)NSDescriptor).getSequence();
      for (XmlNSDescriptor xmlNSDescriptor : sequence) {
        if (xmlNSDescriptor instanceof XmlNSDescriptorImpl) {
          element = ((XmlNSDescriptorImpl)xmlNSDescriptor).getElementDescriptor(text);
          if(element != null) break;
        }
      }
    }
    else {
      element = null;
    }
    return element;
  }

  // Read-only calculation
  protected final XmlAttributeDescriptor[] collectAttributeDescriptors(final XmlTag context) {
    final XmlAttributeDescriptor[] attrDescrs;
    final List<XmlAttributeDescriptor> result = new ArrayList<XmlAttributeDescriptor>();
    for (XmlAttlistDecl attlistDecl : findAttlistDecls(getName())) {
      for (XmlAttributeDecl attributeDecl : attlistDecl.getAttributeDecls()) {
        result.add((XmlAttributeDescriptor)attributeDecl.getMetaData());
      }
    }

    attrDescrs = result.toArray(new XmlAttributeDescriptor[result.size()]);
    return attrDescrs;
  }

  // Read-only calculation
  protected HashMap<String, XmlAttributeDescriptor> collectAttributeDescriptorsMap(final XmlTag context) {
    final HashMap<String, XmlAttributeDescriptor> localADM;
    final XmlAttributeDescriptor[] xmlAttributeDescriptors = getAttributesDescriptors(context);
    localADM = new HashMap<String, XmlAttributeDescriptor>(xmlAttributeDescriptors.length);

    for (final XmlAttributeDescriptor xmlAttributeDescriptor : xmlAttributeDescriptors) {
      localADM.put(xmlAttributeDescriptor.getName(), xmlAttributeDescriptor);
    }
    return localADM;
  }

  private XmlAttlistDecl[] findAttlistDecls(String elementName) {
    final List<XmlAttlistDecl> result = new ArrayList<XmlAttlistDecl>();

    final XmlAttlistDecl[] decls = getAttlistDecls();

    for (final XmlAttlistDecl decl : decls) {
      final XmlElement nameElement = decl.getNameElement();
      if (nameElement != null && nameElement.textMatches(elementName)) {
        result.add(decl);
      }
    }

    return result.toArray(new XmlAttlistDecl[result.size()]);
  }

  private XmlAttlistDecl[] getAttlistDecls() {
    return myAttlistDeclCache.get(this);
  }

  private final XmlAttlistDecl[] doCollectAttlistDecls() {
    final List result = new ArrayList();
    final XmlElement xmlElement = (XmlElement)PsiTreeUtil.getParentOfType(getDeclaration(), ourParentClassesToScanAttributes);
    xmlElement.processElements(new FilterElementProcessor(new ClassFilter(XmlAttlistDecl.class), result), getDeclaration());
    return (XmlAttlistDecl[])result.toArray(new XmlAttlistDecl[result.size()]);
  }

  public int getContentType() {
    if (myElementDecl.getContentSpecElement().isAny()) {
      return CONTENT_TYPE_ANY;
    }
    if (myElementDecl.getContentSpecElement().hasChildren()) {
      return CONTENT_TYPE_CHILDREN;
    }
    if (myElementDecl.getContentSpecElement().isEmpty()) {
      return CONTENT_TYPE_EMPTY;
    }
    if (myElementDecl.getContentSpecElement().isMixed()) {
      return CONTENT_TYPE_MIXED;
    }

    return CONTENT_TYPE_ANY;
  }

  // Read-only calculation
  protected HashMap<String, XmlElementDescriptor> collectElementDescriptorsMap(final XmlTag element) {
    final HashMap<String, XmlElementDescriptor> elementDescriptorsMap;
    final XmlElementDescriptor[] descriptors = getElementsDescriptors(element);
    elementDescriptorsMap = new HashMap<String, XmlElementDescriptor>(descriptors.length);

    for (final XmlElementDescriptor descriptor : descriptors) {
      elementDescriptorsMap.put(descriptor.getName(), descriptor);
    }
    return elementDescriptorsMap;
  }

  public String getQualifiedName() {
    return getName();
  }

  public String getDefaultName() {
    return getName();
  }

  public void setName(final String name) throws IncorrectOperationException {
    myName = name;
  }
}
