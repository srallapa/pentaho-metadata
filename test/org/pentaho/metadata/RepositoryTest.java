package org.pentaho.metadata;

import java.io.File;

import org.junit.Assert;
import org.junit.Test;
import org.pentaho.metadata.model.Domain;
import org.pentaho.metadata.model.LogicalModel;
import org.pentaho.metadata.repository.DomainAlreadyExistsException;
import org.pentaho.metadata.repository.FileBasedMetadataDomainRepository;

public class RepositoryTest {
  @Test
  public void testFileBasedRepository() throws Exception {

    File file = new File("bin/test/DOMAIN.domain.xml");
    
    if (file.exists()) {
      file.delete();
    }
    
    Assert.assertTrue(!file.exists());

    
    Domain domain = TestHelper.getBasicDomain();
    LogicalModel model2 = (LogicalModel)domain.getLogicalModels().get(0).clone();
    model2.setId("MODEL2");
    
    FileBasedMetadataDomainRepository repo = new FileBasedMetadataDomainRepository();
    repo.setDomainFolder("bin/test");
    repo.storeDomain(domain, false);
    
    Assert.assertTrue(file.exists());
    long fileSize = file.length();
    
    domain.addLogicalModel(model2);
    
    try {
      repo.storeDomain(domain, false);
      Assert.fail();
    } catch (Exception e) {
      Assert.assertTrue(e instanceof DomainAlreadyExistsException);
    }
    
    repo.storeDomain(domain, true);
    
    long newFileSize = file.length();
    
    Assert.assertNotSame(fileSize, newFileSize);
    
    Domain domain2 = repo.getDomain(domain.getId());
    Assert.assertEquals(2, domain2.getLogicalModels().size());
    
    repo.removeModel(domain.getId(), "MODEL");
    
    long fileSize3 = file.length();
    
    Assert.assertNotSame(newFileSize, fileSize3);
    
    Domain domain3 = repo.getDomain(domain.getId());
    
    Assert.assertEquals(1, domain3.getLogicalModels().size());
    
    repo.removeModel(domain.getId(), "MODEL2");
    
    Assert.assertTrue(!file.exists());
    
    Domain nullDomain = repo.getDomain(domain.getId());
    
    Assert.assertNull(nullDomain);
    
  }
}