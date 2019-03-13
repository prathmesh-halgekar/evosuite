package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.ga.*;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.comparators.OnlyCrowdingComparator;
import org.evosuite.ga.operators.ranking.CrowdingDistance;
import org.evosuite.ga.operators.selection.TournamentSelectionNoveltyAndRankComparator;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.BudgetConsumptionMonitor;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class NoveltySearch<T extends Chromosome> extends  GeneticAlgorithm<T>{

    private final static Logger logger = LoggerFactory.getLogger(NoveltySearch.class);

    private NoveltyFunction<T> noveltyFunction;

    /** Object used to keep track of the execution time needed to reach the maximum coverage */
    protected final BudgetConsumptionMonitor budgetMonitor;

    /** Keep track of overall suite fitness functions and correspondent test fitness functions */
    protected final Map<TestSuiteFitnessFunction, Class<?>> suiteFitnessFunctions;

    /** Crowding distance measure to use */
    protected CrowdingDistance<T> distance = new CrowdingDistance<T>();

    private LocalCompetition<T> lc = new LocalCompetition<>();

    public NoveltySearch(ChromosomeFactory<T> factory) {

        super(factory);
        budgetMonitor = new BudgetConsumptionMonitor();
        this.suiteFitnessFunctions = new LinkedHashMap<TestSuiteFitnessFunction, Class<?>>();
        for (Properties.Criterion criterion : Properties.CRITERION) {
            TestSuiteFitnessFunction suiteFit = FitnessFunctions.getFitnessFunction(criterion);
            Class<?> testFit = FitnessFunctions.getTestFitnessFunctionClass(criterion);
            this.suiteFitnessFunctions.put(suiteFit, testFit);
        }
    }

    public void setNoveltyFunction(NoveltyFunction<T> function) {
        this.noveltyFunction = function;
    }

    /**
     * Use Novelty Function to do the calculation
     *
     */
    public void calculateNoveltyAndSortPopulation(List<String> uncoveredMethodList, boolean processForNovelty){
        logger.debug("Calculating novelty for " + this.population.size() + " individuals");
        noveltyFunction.calculateNovelty(this.population, noveltyArchive, uncoveredMethodList, processForNovelty);
    }

    /**
     * Sort the population by novelty
     */
    protected void sortPopulation(List<T> population, Map<T, Double> noveltyMap) {
        // TODO: Handle case when no novelty value is stored in map
        // TODO: Use lambdas
        Collections.sort(population, Collections.reverseOrder(new Comparator<T>() {
            @Override
            public int compare(Chromosome c1, Chromosome c2) {
                return Double.compare(noveltyMap.get(c1), noveltyMap.get(c2));
            }
        }));
    }


    protected TestSuiteChromosome generateSuite() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        Archive.getArchiveInstance().getSolutions().forEach(test -> suite.addTest(test));
        return suite;
    }

    /**
     * Return the test cases in the archive as a list.
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    protected List<T> getSolutions() {
        List<T> solutions = new ArrayList<T>();
        Archive.getArchiveInstance().getSolutions().forEach(test -> solutions.add((T) test));
        return solutions;
    }

    public static TestSuiteChromosome result;

    public TestSuiteChromosome getBestIndividual1() {
        TestSuiteChromosome best = this.generateSuite();
        /*if (best.getTestChromosomes().isEmpty()) {

            for (FitnessFunction suiteFitness : this.fitnessFunctions) {
                best.setCoverage(suiteFitness, 0.0);
                best.setFitness(suiteFitness,  1.0);
            }
            return (TestSuiteChromosome) best;
        }*/

        // compute overall fitness and coverage
        this.computeCoverageAndFitness(best);
        result = (TestSuiteChromosome) best;
        return result;
    }
    protected void computeCoverageAndFitness(TestSuiteChromosome suite) {
        for (Map.Entry<TestSuiteFitnessFunction, Class<?>> entry : this.suiteFitnessFunctions
                .entrySet()) {
            TestSuiteFitnessFunction suiteFitnessFunction = entry.getKey();
            Class<?> testFitnessFunction = entry.getValue();

            int numberCoveredTargets =
                    Archive.getArchiveInstance().getNumberOfCoveredTargets(testFitnessFunction);
            int numberUncoveredTargets =
                    Archive.getArchiveInstance().getNumberOfUncoveredTargets(testFitnessFunction);
            int totalNumberTargets = numberCoveredTargets + numberUncoveredTargets;

            double coverage = totalNumberTargets == 0 ? 1.0
                    : ((double) numberCoveredTargets) / ((double) totalNumberTargets);

            suite.setFitness(suiteFitnessFunction, ((double) numberUncoveredTargets));
            suite.setCoverage(suiteFitnessFunction, coverage);
            suite.setNumOfCoveredGoals(suiteFitnessFunction, numberCoveredTargets);
            suite.setNumOfNotCoveredGoals(suiteFitnessFunction, numberUncoveredTargets);
        }
    }

    public TestSuiteChromosome getBestIndividual2(){
        return result;
    }

    /*private List<String> getUncoveredMethodNames(){
            getUncoveredGoals().stream().map((entry) -> entry.)
    }*/

    @Override
    public void initializePopulation() {
        //notifySearchStarted();
        currentIteration = 0;

        // Set up initial population
        generateInitialPopulation(Properties.POPULATION);

        // Determine novelty
        calculateNoveltyAndSortPopulation(getUncoveredMethodNames(), true);

        // Determine fitness
        this.calculateFitness();

        // form sub regions
        // calculate distance w.r.t a fixed point
        // TODO: do only if LC switch is on
        //formSubRegions();

        this.notifyIteration();
    }

    public void formSubRegions(){
        lc.formSubRegions(this.population);
    }

    /**
     * When a test case is changed via crossover and/or mutation, it can contains some
     * primitive variables that are not used as input (or to store the output) of method calls.
     * Thus, this method removes all these "trash" statements.
     *
     * @param chromosome
     * @return true or false depending on whether "unused variables" are removed
     */
    private boolean removeUnusedVariables(T chromosome) {
        int sizeBefore = chromosome.size();
        TestCase t = ((TestChromosome) chromosome).getTestCase();
        List<Integer> to_delete = new ArrayList<Integer>(chromosome.size());
        boolean has_deleted = false;

        int num = 0;
        for (Statement s : t) {
            VariableReference var = s.getReturnValue();
            boolean delete = false;
            delete = delete || s instanceof PrimitiveStatement;
            delete = delete || s instanceof ArrayStatement;
            delete = delete || s instanceof StringPrimitiveStatement;
            if (!t.hasReferences(var) && delete) {
                to_delete.add(num);
                has_deleted = true;
            }
            num++;
        }
        Collections.sort(to_delete, Collections.reverseOrder());
        for (Integer position : to_delete) {
            t.remove(position);
        }
        int sizeAfter = chromosome.size();
        if (has_deleted) {
            logger.debug("Removed {} unused statements", (sizeBefore - sizeAfter));
        }
        return has_deleted;
    }

    /**
     * Method used to mutate an offspring.
     *
     * @param offspring
     * @param parent
     */
    private void mutate(T offspring, T parent) {
        offspring.mutate();
        TestChromosome tch = (TestChromosome) offspring;
        if (!offspring.isChanged()) {
            // if offspring is not changed, we try to mutate it once again
            offspring.mutate();
        }
        if (!this.hasMethodCall(offspring)) {
            tch.setTestCase(((TestChromosome) parent).getTestCase().clone());
            boolean changed = tch.mutationInsert();
            if (changed) {
                for (Statement s : tch.getTestCase()) {
                    s.isValid();
                }
            }
            offspring.setChanged(changed);
        }
        this.notifyMutation(offspring);
    }

    /**
     * This method checks whether the test has only primitive type statements. Indeed,
     * crossover and mutation can lead to tests with no method calls (methods or constructors
     * call), thus, when executed they will never cover something in the class under test.
     *
     * @param test to check
     * @return true if the test has at least one method or constructor call (i.e., the test may
     * cover something when executed; false otherwise
     */
    private boolean hasMethodCall(T test) {
        boolean flag = false;
        TestCase tc = ((TestChromosome) test).getTestCase();
        for (Statement s : tc) {
            if (s instanceof MethodStatement) {
                MethodStatement ms = (MethodStatement) s;
                boolean isTargetMethod = ms.getDeclaringClassName().equals(Properties.TARGET_CLASS);
                if (isTargetMethod) {
                    return true;
                }
            }
            if (s instanceof ConstructorStatement) {
                ConstructorStatement ms = (ConstructorStatement) s;
                boolean isTargetMethod = ms.getDeclaringClassName().equals(Properties.TARGET_CLASS);
                if (isTargetMethod) {
                    return true;
                }
            }
        }
        return flag;
    }


    @Override
    protected void evolve() {

        List<T> offspringPopulation = this.breedNextGeneration();

        if(Properties.NOVELTY_SELECTION){
            this.population.clear();
            this.population.addAll(offspringPopulation);
        }
        else if(Properties.DISTANCE_FOR_NOVELTY){
            this.population.clear();
            this.population.addAll(offspringPopulation);
            this.rankingFunction.computeRankingAssignment(this.population, this.getUncoveredGoals());
            this.distance.fastEpsilonDominanceAssignment(this.population, this.getUncoveredGoals());
        }
        else{
            // Create the union of parents and offSpring
            List<T> union = new ArrayList<T>();
            union.addAll(this.population);
            union.addAll(offspringPopulation);

            Set<FitnessFunction<T>> uncoveredGoals = this.getUncoveredGoals();

            // Ranking the union
            logger.debug("Union Size =" + union.size());
            // Ranking the union using the best rank algorithm (modified version of the non dominated sorting algorithm)
            this.rankingFunction.computeRankingAssignment(union, uncoveredGoals);

            int remain = this.population.size();
            int index = 0;
            List<T> front = null;
            this.population.clear();

            // Obtain the next front
            front = this.rankingFunction.getSubfront(index);

            while ((remain > 0) && (remain >= front.size()) && !front.isEmpty()) {
                // Assign crowding distance to individuals
                if(Properties.RANK_AND_DISTANCE_SELECTION){
                    this.distance.fastEpsilonDominanceAssignment(front, uncoveredGoals);
                }
                // Add the individuals of this front
                this.population.addAll(front);

                // Decrement remain
                remain = remain - front.size();

                // Obtain the next front
                index++;
                if (remain > 0) {
                    front = this.rankingFunction.getSubfront(index);
                }
            }
            // Remain is less than front(index).size, insert only the best one
            if (remain > 0 && !front.isEmpty()) { // front contains individuals to insert
                if(Properties.RANK_AND_DISTANCE_SELECTION) {
                    this.distance.fastEpsilonDominanceAssignment(front, uncoveredGoals);
                    Collections.sort(front, new OnlyCrowdingComparator());
                }
                for (int k = 0; k < remain; k++) {
                    this.population.add(front.get(k));
                }

                remain = 0;
            }

        }
        this.currentIteration++;



        /*List<T> newGeneration = new ArrayList<T>();
        // changes start
        for (int i = 0; i < Properties.POPULATION / 2 && !this.isFinished(); i++) {
            // select best individuals
            T parent1 = null;
            T parent2 = null;
            if(Properties.RANK_AND_NOVELTY_SELECTION){ // by default true
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankBasedCompetition(true);
                parent1 = this.selectionFunction.select(this.population);
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankBasedCompetition(true);
                parent2 = this.selectionFunction.select(this.population);
            }
            else if(Properties.NOVELTY_SELECTION){
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankBasedCompetition(false);
                parent1 = this.selectionFunction.select(this.population);
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankBasedCompetition(false);
                parent2 = this.selectionFunction.select(this.population);
            }

            T offspring1 = (T) parent1.clone();
            T offspring2 = (T) parent2.clone();
            // apply crossover
            try {
                if (Randomness.nextDouble() <= Properties.CROSSOVER_RATE) {
                    this.crossoverFunction.crossOver(offspring1, offspring2);
                }
            } catch (ConstructionFailedException e) {
                logger.debug("CrossOver failed.");
                continue;
            }

            this.removeUnusedVariables(offspring1);
            this.removeUnusedVariables(offspring2);

            // apply mutation on offspring1
            this.mutate(offspring1, parent1);
            if (offspring1.isChanged()) {
                this.clearCachedResults(offspring1);
                offspring1.updateAge(this.currentIteration);
                this.calculateFitness(offspring1);
                newGeneration.add(offspring1);
            }
            // apply mutation on offspring2
            this.mutate(offspring2, parent2);
            if (offspring2.isChanged()) {
                this.clearCachedResults(offspring2);
                offspring2.updateAge(this.currentIteration);
                this.calculateFitness(offspring2);
                newGeneration.add(offspring2);
            }

        }
        // Add new randomly generate tests
        for (int i = 0; i < Properties.POPULATION * Properties.P_TEST_INSERTION; i++) {
            T tch = null;
            if (this.getCoveredGoals().size() == 0 || Randomness.nextBoolean()) {
                tch = this.chromosomeFactory.getChromosome();
                tch.setChanged(true);
            } else {
                tch = (T) Randomness.choice(this.getSolutions()).clone();
                tch.mutate(); tch.mutate(); // TODO why is it mutated twice?
            }
            if (tch.isChanged()) {
                tch.updateAge(this.currentIteration);
                this.calculateFitness(tch);
                newGeneration.add(tch);
            }
        }
        logger.info("Number of offsprings = {}", newGeneration.size());
        // changes end


        this.population = newGeneration;
        //archive
        updateFitnessFunctionsAndValues();
        //
        currentIteration++;*/
    }
    protected List<T> breedNextGeneration() {
        List<T> offspringPopulation = new ArrayList<T>(Properties.POPULATION);
        // we apply only Properties.POPULATION/2 iterations since in each generation
        // we generate two offsprings
        for (int i = 0; i < Properties.POPULATION / 2 && !this.isFinished(); i++) {
            // select best individuals
            T parent1 = null;
            T parent2 = null;
            if(Properties.RANK_AND_NOVELTY_SELECTION){ // by default true
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankAndDistanceBasedCompetition(false);
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setOnlyNoveltyBasedCompetition(false);

                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankAndNoveltyBasedCompetition(true);
                parent1 = this.selectionFunction.select(this.population);
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankAndNoveltyBasedCompetition(true);
                parent2 = this.selectionFunction.select(this.population);
            }
            else if(Properties.RANK_AND_DISTANCE_SELECTION){
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankAndNoveltyBasedCompetition(false);
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setOnlyNoveltyBasedCompetition(false);

                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankAndDistanceBasedCompetition(true);
                parent1 = this.selectionFunction.select(this.population);
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankAndDistanceBasedCompetition(true);
                parent2 = this.selectionFunction.select(this.population);
            }
            else if(Properties.NOVELTY_SELECTION || Properties.DISTANCE_FOR_NOVELTY){
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankAndDistanceBasedCompetition(false);
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setRankAndNoveltyBasedCompetition(false);

                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setOnlyNoveltyBasedCompetition(true);
                parent1 = this.selectionFunction.select(this.population);
                ((TournamentSelectionNoveltyAndRankComparator)this.selectionFunction).setOnlyNoveltyBasedCompetition(true);
                parent2 = this.selectionFunction.select(this.population);
            }
            T offspring1 = (T) parent1.clone();
            T offspring2 = (T) parent2.clone();
            // apply crossover
            try {
                if (Randomness.nextDouble() <= Properties.CROSSOVER_RATE) {
                    this.crossoverFunction.crossOver(offspring1, offspring2);
                }
            } catch (ConstructionFailedException e) {
                logger.debug("CrossOver failed.");
                continue;
            }

            this.removeUnusedVariables(offspring1);
            this.removeUnusedVariables(offspring2);

            // apply mutation on offspring1
            this.mutate(offspring1, parent1);
            if (offspring1.isChanged()) {
                this.clearCachedResults(offspring1);
                offspring1.updateAge(this.currentIteration);
                this.calculateFitness(offspring1);
                offspringPopulation.add(offspring1);
            }

            // apply mutation on offspring2
            this.mutate(offspring2, parent2);
            if (offspring2.isChanged()) {
                this.clearCachedResults(offspring2);
                offspring2.updateAge(this.currentIteration);
                this.calculateFitness(offspring2);
                offspringPopulation.add(offspring2);
            }
        }
        // Add new randomly generate tests
        for (int i = 0; i < Properties.POPULATION * Properties.P_TEST_INSERTION; i++) {
            T tch = null;
            if (this.getCoveredGoals().size() == 0 || Randomness.nextBoolean()) {
                tch = this.chromosomeFactory.getChromosome();
                tch.setChanged(true);
            } else {
                tch = (T) Randomness.choice(this.getSolutions()).clone();
                tch.mutate(); tch.mutate(); // TODO why is it mutated twice?
            }
            if (tch.isChanged()) {
                tch.updateAge(this.currentIteration);
                this.calculateFitness(tch);
                offspringPopulation.add(tch);
            }
        }
        logger.info("Number of offsprings = {}", offspringPopulation.size());
        return offspringPopulation;
    }

    /**
     * Returns the goals that have been covered by the test cases stored in the archive.
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    protected Set<FitnessFunction<T>> getCoveredGoals() {
        Set<FitnessFunction<T>> coveredGoals = new LinkedHashSet<FitnessFunction<T>>();
        Archive.getArchiveInstance().getCoveredTargets()
                .forEach(ff -> coveredGoals.add((FitnessFunction<T>) ff));
        return coveredGoals;
    }

    /**
     * This method clears the cached results for a specific chromosome (e.g., fitness function
     * values computed in previous generations). Since a test case is changed via crossover
     * and/or mutation, previous data must be recomputed.
     *
     * @param chromosome TestChromosome to clean
     */
    private void clearCachedResults(T chromosome) {
        ((TestChromosome) chromosome).clearCachedMutationResults();
        ((TestChromosome) chromosome).clearCachedResults();
        ((TestChromosome) chromosome).clearMutationHistory();
        ((TestChromosome) chromosome).getFitnessValues().clear();
    }

    protected void addUncoveredGoal(FitnessFunction<T> goal) {
        Archive.getArchiveInstance().addTarget((TestFitnessFunction) goal);
    }

    protected int getNumberOfUncoveredGoals() {
        return Archive.getArchiveInstance().getNumberOfUncoveredTargets();
    }

    protected Set<FitnessFunction<T>> getUncoveredGoals() {
        Set<FitnessFunction<T>> uncoveredGoals = new LinkedHashSet<FitnessFunction<T>>();
        Archive.getArchiveInstance().getUncoveredTargets()
                .forEach(ff -> uncoveredGoals.add((FitnessFunction<T>) ff));
        return uncoveredGoals;
    }

    private List<String> getUncoveredMethodNames(){
        Set<String> uncoveredMethodNames = new HashSet<>();
        Set<FitnessFunction<T>> uncoveredGoals = getUncoveredGoals();
        Iterator i = uncoveredGoals.iterator();
        while (i.hasNext()){
            uncoveredMethodNames.add(((BranchCoverageTestFitness)i.next()).getBranchGoal().getMethodName());
        }
        return new ArrayList<>(uncoveredMethodNames);
    }

    @Override
    public void generateSolution() {
        logger.info("executing generateSolution function");

        // keep track of covered goals
        this.fitnessFunctions.forEach(this::addUncoveredGoal);

        if (population.isEmpty())
            initializePopulation();

        // Calculate dominance ranks
        if(Properties.RANK_AND_NOVELTY_SELECTION){
            this.rankingFunction.computeRankingAssignment(this.population, this.getUncoveredGoals());
        }
        // Properties.DISTANCE_FOR_NOVELTY added for Experimental purpose
        else if (Properties.DISTANCE_FOR_NOVELTY) {
            this.rankingFunction.computeRankingAssignment(this.population, this.getUncoveredGoals());
            this.distance.fastEpsilonDominanceAssignment(this.population, this.getUncoveredGoals()); // distance for the entire population instead of each of the fronts.
        }

        else if (Properties.RANK_AND_DISTANCE_SELECTION) {
            this.rankingFunction.computeRankingAssignment(this.population, this.getUncoveredGoals());
            for (int i = 0; i < this.rankingFunction.getNumberOfSubfronts(); i++) {
                this.distance.fastEpsilonDominanceAssignment(this.rankingFunction.getSubfront(i), this.getUncoveredGoals());
            }
        }

        else if(Properties.SWITCH_NOVELTY_FITNESS){
            this.rankingFunction.computeRankingAssignment(this.population, this.getUncoveredGoals());
            for (int i = 0; i < this.rankingFunction.getNumberOfSubfronts(); i++) {
                this.distance.fastEpsilonDominanceAssignment(this.rankingFunction.getSubfront(i), this.getUncoveredGoals());
            }
        }



        logger.warn("Starting evolution of novelty search algorithm");

        while (!isFinished() && this.getNumberOfUncoveredGoals() > 0) {
            logger.warn("Current population: " + getAge() + "/" + Properties.SEARCH_BUDGET);

            // for experiments
            boolean processForNovelty = true;
            if(Properties.SWITCH_NOVELTY_FITNESS){
                if((currentIteration%Properties.SWITCH_ITERATIONS) == 0){
                    Properties.RANK_AND_NOVELTY_SELECTION = false;
                    Properties.RANK_AND_DISTANCE_SELECTION = false;
                    Properties.NOVELTY_SELECTION = true;
                    calculateNoveltyAndSortPopulation(getUncoveredMethodNames(), true);
                    processForNovelty = false;
                }else{
                    Properties.RANK_AND_DISTANCE_SELECTION = true;
                    Properties.NOVELTY_SELECTION = false;
                    Properties.RANK_AND_NOVELTY_SELECTION = false;
                    processForNovelty = false;
                }
            }
            if (Properties.RANK_AND_DISTANCE_SELECTION || Properties.DISTANCE_FOR_NOVELTY) {
                processForNovelty = false;
            }

            /**/

            long startTime = System.currentTimeMillis();
            evolve();

            // Calculate dominance ranks
            //this.rankingFunction.computeRankingAssignment(this.population, this.getUncoveredGoals());

            // TODO: Sort by novelty
            calculateNoveltyAndSortPopulation(getUncoveredMethodNames(), processForNovelty);

            long endTime = System.currentTimeMillis();

            this.notifyIteration();
            System.out.println("Execution Time for Generation : "+this.currentIteration+" is (ms):"+(endTime-startTime));
        }

        System.out.println("Archive size after all the generations : "+this.noveltyArchive.size());
        // storing the time needed to reach the maximum coverage
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Time2MaxCoverage,
                this.budgetMonitor.getTime2MaxCoverage());
        notifySearchFinished();

    }
}
