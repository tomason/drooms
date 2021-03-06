package org.drooms.impl.util;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.kie.api.KieServices;
import org.kie.api.builder.KieRepository;
import org.kie.api.builder.ReleaseId;
import org.kie.api.io.Resource;

public class DroomsStrategyValidatorTest {

    @Test
    public void testInvalidReleaseId() {
        ReleaseId releaseId = KieServices.Factory.get().newReleaseId("this", "artifact", "is.invalid");

        DroomsStrategyValidator validator = new DroomsStrategyValidator(releaseId);
        Assert.assertFalse(validator.isValid());
        List<String> errors = validator.getErrors();
        Assert.assertEquals("Unexpected number of errors", 1, errors.size());
        Assert.assertEquals("Wrong error message", "Cannot find KieModule: this:artifact:is.invalid", errors.get(0));
    }

    @Test
    public void testMissingDefaultKieBase() {
        ReleaseId releaseId = deployArtifact("test-strategy-1.0");

        DroomsStrategyValidator validator = new DroomsStrategyValidator(releaseId);
        Assert.assertFalse(validator.isValid());
        List<String> errors = validator.getErrors();
        Assert.assertEquals("Unexpected number of errors", 1, errors.size());
        // FIXME a typo in Kie API
        Assert.assertEquals("Wrong error message", "Cannot find a defualt KieBase", errors.get(0));
    }

    @Test
    public void testMissingEntryPoints() {
        ReleaseId releaseId = deployArtifact("test-strategy-2.0");

        DroomsStrategyValidator validator = new DroomsStrategyValidator(releaseId);
        Assert.assertFalse(validator.isValid());
        List<String> errors = validator.getErrors();
        Assert.assertEquals("Unexpected number of errors", 2, errors.size());
        Assert.assertTrue("Wrong error message", errors.contains("Entry point 'playerEvents' not declared."));
        Assert.assertTrue("Wrong error message", errors.contains("Entry point 'gameEvents' not declared."));
    }

    @Test
    public void testMissingLogger() {
        ReleaseId releaseId = deployArtifact("test-strategy-3.0");

        DroomsStrategyValidator validator = new DroomsStrategyValidator(releaseId);
        Assert.assertTrue(validator.isValid());
        Assert.assertFalse(validator.isClean());
        List<String> warnings = validator.getWarnings();
        Assert.assertEquals("Unexpected number of warnings", 2, warnings.size());
        Assert.assertTrue("Wrong warning message", warnings.contains("Global 'logger' of type 'org.slf4j.Logger' not declared."));
        Assert.assertTrue("Wrong warning message", warnings.contains("Global 'tracker' of type 'org.drooms.impl.logic.PathTracker' not declared."));
    }

    private ReleaseId deployArtifact(String jarName) {
        KieServices ks = KieServices.Factory.get();
        Resource resource = ks.getResources().newClassPathResource(jarName, getClass());

        KieRepository repository = ks.getRepository();
        return repository.addKieModule(resource).getReleaseId();
    }
}
