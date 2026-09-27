package benchmark;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BenchmarkResultsTest {
    @Test
    public void rejectsMissingDuplicateForbiddenAndWrongSizedResults() throws Exception {
        Path root=Files.createTempDirectory("benchmark-validation-");
        try {
            Path functional=root.resolve("input.ofn"), standard=root.resolve("input.oprt"), mis=root.resolve("input128.oprt");
            Files.write(functional,new byte[20]); Files.write(standard,new byte[10]); Files.write(mis,new byte[12]);
            var entries=List.of(new DatasetFiles.Entry("input",functional,standard,mis));
            Path sizes=root.resolve("sizes.csv"), results=root.resolve("results.csv");
            Files.write(sizes,List.of("Ontology,InputVariant,OutputSizeBytes","input,standard,11","input,MIS_128,11"));
            List<String> rows=List.of(BenchmarkResults.HEADER,
                "parse,Functional,input,1.5,20,0,100,2.000000,0.500000",
                "render,Functional,input,1.5,20,21,100,2.000000,0.500000",
                "parse,ProtocOWL,input,1.5,10,0,100,1.000000,0.000000",
                "render,ProtocOWL,input,1.5,10,11,100,1.000000,0.000000",
                "parse,ProtocOWL_128,input,1.5,12,0,100,1.200000,0.166667");
            Files.write(results,rows); BenchmarkResults.validate(results,sizes,entries);
            for (int kind=0;kind<5;kind++) {
                List<String> broken=new ArrayList<>(rows);
                switch (kind) {
                    case 0 -> broken.removeLast();
                    case 1 -> broken.set(5,rows.get(1));
                    case 2 -> broken.set(5,rows.get(5).replace("parse,", "render,"));
                    case 3 -> broken.set(4,rows.get(4).replace(",10,11,", ",10,12,"));
                    case 4 -> broken.set(1,rows.get(1).replace(",20,0,", ",19,0,"));
                }
                Files.write(results,broken);
                try { BenchmarkResults.validate(results,sizes,entries); Assert.fail("Invalid CSV accepted, case " + kind); }
                catch (IOException expected) { Assert.assertFalse(expected.getMessage().isBlank()); }
            }
        } finally {
            try (var files=Files.walk(root)) { for (Path p:files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
}
