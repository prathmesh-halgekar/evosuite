package org.evosuite.coverage.dataflow;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.objectweb.asm.Opcodes;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class FeatureFactory implements Serializable {

    private static Map<Integer, Feature> features = new HashMap<Integer, Feature>();
    private static Map<BytecodeInstruction, Integer> knownInstructions = new HashMap<BytecodeInstruction, Integer>();
    private static int defCounter = 0;

    /**
     * This method recognizes all the instructions which ASMWrapper.isDefinition() returns as true.
     * It registers the instruction as a Feature only if the instruction is seen for the first time.
     * Instructions are verified as redundant or unique depending on their variable name.
     * <p>
     * PUTFIELD instructions
     *
     * @param d BytecodeInstruction
     * @return a boolean
     */
    public static boolean registerAsFeature(BytecodeInstruction d) {
        if (null != knownInstructions.get(d)) {
            return false;
        } else {

            if (null != getFeatureByVarName(d.getVariableName()))
                return true;

            if (d.getASMNode().getOpcode() == Opcodes.POP)
                return true;

            defCounter++;
            knownInstructions.put(d, defCounter);
            String var = d.getVariableName();
            Feature feature = new Feature();
            feature.setVariableName(var);
            features.put(defCounter, feature);
        }
        return true;
    }

    public static Map<Integer, Feature> getFeatures() {
        return features;
    }

    public static Feature getFeatureByVarName(String name) {
        Map.Entry<Integer, Feature> entry = getEntryByName(name);
        if (null != entry)
            return entry.getValue();
        else
            return null;
    }

    public static Integer getFeatureIdByVarName(String name) {
        Map.Entry<Integer, Feature> entry = getEntryByName(name);
        if (null != entry)
            return entry.getKey();
        else
            return null;
    }

    public static Map.Entry<Integer, Feature> getEntryByName(String varName) {
        for (Map.Entry<Integer, Feature> entry : features.entrySet()) {
            Feature feature = entry.getValue();
            if (feature.getVariableName().equals(varName))
                return entry;
        }
        return null;
    }

    public static BytecodeInstruction getInstructionById(int id) {
        for (Map.Entry<BytecodeInstruction, Integer> entry : knownInstructions.entrySet()) {
            int val = entry.getValue();
            if (val == id)
                return entry.getKey();
        }
        return null;
    }

}
