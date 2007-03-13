/**
 * Copyright 2006 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * Permission is hereby granted, free of charge, to use and distribute
 * this software and its documentation without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of this work, and to
 * permit persons to whom this work is furnished to do so, subject to
 * the following conditions:
 * 
 * 1. The code must retain the above copyright notice, this list of
 *    conditions and the following disclaimer.
 * 2. Any modifications must be clearly marked as such.
 * 3. Original authors' names are not deleted.
 * 4. The authors' names are not used to endorse or promote products
 *    derived from this software without specific prior written
 *    permission.
 *
 * DFKI GMBH AND THE CONTRIBUTORS TO THIS WORK DISCLAIM ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE, INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS, IN NO EVENT SHALL DFKI GMBH NOR THE
 * CONTRIBUTORS BE LIABLE FOR ANY SPECIAL, INDIRECT OR CONSEQUENTIAL
 * DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR
 * PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS
 * ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 */
package de.dfki.lt.mary.unitselection.voiceimport;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import de.dfki.lt.mary.unitselection.FeatureFileIndexer;
import de.dfki.lt.mary.unitselection.MaryNode;
import de.dfki.lt.mary.unitselection.cart.CARTWagonFormat;
import de.dfki.lt.mary.unitselection.cart.LeafNode;
import de.dfki.lt.mary.unitselection.featureprocessors.FeatureDefinition;
import de.dfki.lt.mary.unitselection.featureprocessors.FeatureVector;
import de.dfki.lt.mary.unitselection.FeatureArrayIndexer;
import de.dfki.lt.mary.unitselection.FeatureFileReader;
import de.dfki.lt.mary.unitselection.MCepTimelineReader;
import de.dfki.lt.mary.unitselection.UnitFileReader;
import de.dfki.lt.mary.unitselection.Datagram;
import de.dfki.lt.mary.unitselection.MCepDatagram;
import de.dfki.lt.mary.unitselection.Unit;

import de.dfki.lt.mary.MaryProperties;

public class CARTBuilder implements VoiceImportComponent {
    
    private DatabaseLayout databaseLayout;
    
    public CARTBuilder(DatabaseLayout databaseLayout){
        this.databaseLayout = databaseLayout;
    }
    
    //for testing
    public static void main(String[] args) throws Exception
    {
        //build database layout
        DatabaseLayout db = new DatabaseLayout();
        //build CARTBuilder and run compute
        CARTBuilder cartBuilder = new CARTBuilder(db);
        cartBuilder.compute();
    }
    
     public boolean compute() throws Exception{
         long time = System.currentTimeMillis();
         //read in the features with feature file indexer
         System.out.println("Reading feature file ...");
         String featureFile = databaseLayout.targetFeaturesFileName();
         FeatureFileReader ffr = FeatureFileReader.getFeatureFileReader(featureFile);
         FeatureVector[] featureVectorsCopy = ffr.getCopyOfFeatureVectors();
         FeatureDefinition featureDefinition = ffr.getFeatureDefinition(); 
         //remove the feature vectors of edge units
         List fVList = new ArrayList();
         int edgeIndex = 
             featureDefinition.getFeatureIndex(FeatureDefinition.EDGEFEATURE);
         for (int i=0;i<featureVectorsCopy.length;i++){
             FeatureVector nextFV = featureVectorsCopy[i];
             if (!nextFV.isEdgeVector(edgeIndex)) fVList.add(nextFV);
         }
         int fVListSize = fVList.size();
         int removed = featureVectorsCopy.length - fVListSize;
         System.out.println("Removed "+removed+" edge vectors; "
                 +"remaining vectors : "+fVListSize);
         FeatureVector[] featureVectors = new FeatureVector[fVListSize];
         for (int i=0;i<featureVectors.length;i++){
             featureVectors[i] = (FeatureVector) fVList.get(i);
         }
         
         
         FeatureArrayIndexer fai = new FeatureArrayIndexer(featureVectors, featureDefinition);
         System.out.println(" ... done!");
        
         //read in the feature sequence
         //open the file
         System.out.println("Reading feature sequence ...");
         String featSeqFile = databaseLayout.featSequenceFileName();
         BufferedReader buf = new BufferedReader(
                 new FileReader(new File(featSeqFile)));
         //each line contains one feature
         String line = buf.readLine();
         //collect features in a list
         List features = new ArrayList();
         while (line != null){
             // Skip empty lines and lines starting with #:
             if (!(line.trim().equals("") || line.startsWith("#"))){
                 features.add(line.trim());
             }
             line = buf.readLine();
         }
         //convert list to int array
         int[] featureSequence = new int[features.size()];
         for (int i=0;i<features.size();i++){
             featureSequence[i] = 
                 featureDefinition.getFeatureIndex((String)features.get(i));
         }
         System.out.println(" ... done!"); 

         //sort the features according to feature sequence
         System.out.println("Sorting features ...");
         fai.deepSort(featureSequence);
         System.out.println(" ... done!");
         //get the resulting tree
         MaryNode topLevelTree = fai.getTree();
         //topLevelTree.toStandardOut(ffi);
         
         //convert the top-level CART to Wagon Format
         System.out.println("Building CART from tree ...");
         CARTWagonFormat topLevelCART = new CARTWagonFormat(topLevelTree,fai);
         System.out.println(" ... done!");
        
         
         boolean callWagon = System.getProperty("db.cartbuilder.callwagon", "true").equals("true");
         
         if (callWagon) {
             if (!replaceLeaves(topLevelCART,featureDefinition));
             	System.out.println("Could not replace leaves");
             	return false;
         }
         
         //dump big CART to binary file
         String destinationFile = databaseLayout.cartFileName();
         dumpCART(destinationFile,topLevelCART);
         //Dump the resulting Cart to text file
         PrintWriter pw = 
                new PrintWriter(
                        new FileWriter(
                                new File("./mary_files/cartTextDump.txt")));
         topLevelCART.toTextOut(pw);
         //say how long you took
         long timeDiff = System.currentTimeMillis() - time;
         System.out.println("Processing took "+timeDiff+" milliseconds.");
         
         
         return true;
     }
    
     
     /**
     * Read in the CARTs from festival/trees/ directory,
     * and store them in a CARTMap
     * 
     * @param festvoxDirectory the festvox directory of a voice
     */
    public CARTWagonFormat importCART(String filename,
                            FeatureDefinition featDef)
    throws IOException
    {
        try{
            //open CART-File
            System.out.println("Reading CART from "+filename+" ...");
            //build and return CART
            CARTWagonFormat cart = new CARTWagonFormat();
            cart.load(filename,featDef,null);
            //cart.toStandardOut();
            System.out.println(" ... done!");
            return cart;
        } catch (IOException ioe){
            IOException newIOE = new IOException("Error reading CART");
            newIOE.initCause(ioe);
            throw newIOE;
        }
    }
       
    /**
     * Dump the CARTs in the cart map
     * to destinationDir/CARTS.bin
     * 
     * @param destDir the destination directory
     */
    public void dumpCART(String destFile,
                        CARTWagonFormat cart)
    throws IOException
    {
        System.out.println("Dumping CART to "+destFile+" ...");
        
        //Open the destination file (cart.bin) and output the header
        DataOutputStream out = new DataOutputStream(new
                BufferedOutputStream(new 
                FileOutputStream(destFile)));
        //create new CART-header and write it to output file
        MaryHeader hdr = new MaryHeader(MaryHeader.CARTS);
        hdr.writeTo(out);

        //write number of nodes
        out.writeInt(cart.getNumNodes());
        String name = "";
        //dump name and CART
        out.writeUTF(name);
        //dump CART
        cart.dumpBinary(out);
      
        //finish
        out.close();
        System.out.println(" ... done\n");
    }     
    
    /**
     * For each leaf in the CART, 
     * run Wagon on the feature vectors in this CART,
     * and replace leaf by resulting CART
     *  
     * @param topLevelCART the CART
     * @param featureDefinition the definition of the features
     */
    public boolean replaceLeaves(CARTWagonFormat cart,
            				FeatureDefinition featureDefinition)
    throws IOException
    {
        try {
            System.out.println("Replacing Leaves ...");
            //TODO: find out why the cart has so many (empty) nodes
            System.out.println("Cart has "+cart.getNumNodes()+" nodes");
            
            
            //create wagon dir if it does not exist
            File wagonDir = new File(databaseLayout.wagonDirName());
            if (!wagonDir.exists()){
                wagonDir.mkdir();
            }
            //get the filenames for the various files used by wagon
            String wagonDirName = databaseLayout.wagonDirName();
            String featureDefFile = wagonDirName + "/" 
                                + databaseLayout.wagonDescFile();
            String featureVectorsFile = databaseLayout.wagonFeatsFile();
            String cartFile = databaseLayout.wagonCartFile();
            String distanceTableFile = databaseLayout.wagonDistTabsFile();
            //dump the feature definitions
            PrintWriter out = new PrintWriter(new 
                			FileOutputStream(new 
                			        File(featureDefFile)));
            featureDefinition.generateAllDotDescForWagon(out);
            out.close();

            //build new WagonCaller
            WagonCaller wagonCaller = new WagonCaller(featureDefFile);
           
            int numProcesses = 1;
            String np = MaryProperties.getProperty("numProcesses");
            if (np != null){
                numProcesses = Integer.parseInt(np);
            }
            
            for (LeafNode leaf = cart.getFirstLeafNode(); leaf != null; leaf = leaf.getNextLeafNode()) {
                /* call Wagon successively */
                //go through the CART
                FeatureVector[] featureVectors = leaf.getFeatureVectors();
                //dump the feature vectors
                System.out.println("Dumping feature vectors");
                dumpFeatureVectors(featureVectors, featureDefinition,wagonDirName+"/"+featureVectorsFile);
                //dump the distance tables
                buildAndDumpDistanceTables(featureVectors,wagonDirName+"/"+distanceTableFile,featureDefinition);
                //call Wagon
                System.out.println("Calling wagon");
                if (!wagonCaller.callWagon(wagonDirName+"/"+featureVectorsFile,wagonDirName+"/"+distanceTableFile,wagonDirName+"/"+cartFile))
                     return false;
                //read in the resulting CART
                System.out.println("Reading CART");
                BufferedReader buf = new BufferedReader(
                        new FileReader(new File(wagonDirName+"/"+cartFile)));
                CARTWagonFormat newCART = new CARTWagonFormat(buf,featureDefinition);    
                buf.close();
                //replace the leaf by the CART
                System.out.println("Replacing leaf");
                CARTWagonFormat.replaceLeafByCart(newCART, leaf);
                System.out.println("Cart has "+cart.getNumNodes()+" nodes");
            }           
              
        } catch (IOException ioe) {
            IOException newIOE = new IOException("Error replacing leaves");
            newIOE.initCause(ioe);
            throw newIOE;
        }
        System.out.println(" ... done!");
        return true;
    }
    
    /**
     * Dump the given feature vectors to a file with the given filename
     * @param featureVectors the feature vectors
     * @param featDef the feature definition
     * @param filename the filename
     */
    public void dumpFeatureVectors(FeatureVector[] featureVectors,
            					FeatureDefinition featDef,
            					String filename) throws FileNotFoundException{
        //open file 
        PrintWriter out = new PrintWriter(new
                BufferedOutputStream(new 
                        FileOutputStream(filename)));
        //get basic feature info
        int numByteFeats = featDef.getNumberOfByteFeatures();
        int numShortFeats = featDef.getNumberOfShortFeatures();
        int numFloatFeats = featDef.getNumberOfContinuousFeatures();
        //loop through the feature vectors
        for (int i=0; i<featureVectors.length;i++){
            // Print the feature string
            out.print( i+" "+featDef.toFeatureString( featureVectors[i] ) );
            //print a newline if this is not the last vector
            if (i+1 != featureVectors.length){
                out.print("\n");
            }
        }
        //dump and close
        out.flush();
        out.close();
    }
    
    /**
     * Build the distance tables for the units 
     * from which we have the feature vectors
     * and dump them to a file with the given filename
     * @param featureVectors the feature vectors of the units
     * @param filename the filename
     */
    public void buildAndDumpDistanceTables (FeatureVector[] featureVectors, String filename,
            FeatureDefinition featDef ) throws FileNotFoundException {
        
        System.out.println( "Computing distance matrix");
        
        /* Dereference the number of units once and for all */
        int numUnits = featureVectors.length;
        /* Load the MelCep timeline and the unit file */
        MCepTimelineReader tlr = null;
        try {
            tlr = new MCepTimelineReader( databaseLayout.melcepTimelineFileName() );
        }
        catch ( IOException e ) {
            throw new RuntimeException( "Failed to read the Mel-Cepstrum timeline [" + databaseLayout.melcepTimelineFileName()
                    + "] due to the following IOException: ", e );
        }
        UnitFileReader ufr = null;
        try {
            ufr = new UnitFileReader( databaseLayout.unitFileName() );
        }
        catch ( IOException e ) {
            throw new RuntimeException( "Failed to read the unit file [" + databaseLayout.unitFileName()
                    + "] due to the following IOException: ", e );
        }
        /* Read the Mel Cepstra for each unit, and cumulate
         * their sufficient statistics in the same loop */
        double[][][] melCep = new double[numUnits][][];
        double val = 0;
        double[] sum = new double[tlr.getOrder()];
        double[] sumSq = new double[tlr.getOrder()];
        double[] sigma2 = new double[tlr.getOrder()];
        double N = 0.0;
        for ( int i = 0; i < numUnits; i++ ) {
            //System.out.println( "FEATURE_VEC_IDX=" + i + " UNITIDX=" + featureVectors[i].getUnitIndex() );
            /* Read the datagrams for the current unit */
            Datagram[] buff = null;
            MCepDatagram[] dat = null;
            //System.out.println( featDef.toFeatureString( featureVectors[i] ) );
            try {
                buff = tlr.getDatagrams( ufr.getUnit(featureVectors[i].getUnitIndex()), ufr.getSampleRate() );
                //System.out.println( "NUMFRAMES=" + buff.length );
                dat = new MCepDatagram[buff.length];
                for ( int d = 0; d < buff.length; d++ ) {
                    dat[d] = (MCepDatagram)( buff[d] );
                }
            }
            catch ( Exception e ) {
                throw new RuntimeException( "Failed to read the datagrams for unit number [" + featureVectors[i].getUnitIndex()
                        + "] from the Mel-cepstrum timeline due to the following Exception: ", e );
            }
            N += (double)(dat.length); // Update the frame counter
            melCep[i] = new double[dat.length][];
            for ( int j = 0; j < dat.length; j++ ) {
                melCep[i][j] = dat[j].getCoeffsAsDouble();
                /* Cumulate the sufficient statistics */
                for ( int k = 0; k < tlr.getOrder(); k++ ) {
                    val = melCep[i][j][k];
                    sum[k] += val;
                    sumSq[k] += (val*val);
                }
            }
        }
        /* Finalize the variance calculation */
        for ( int k = 0; k < tlr.getOrder(); k++ ) {
            val = sum[k];
            sigma2[k] = ( sumSq[k] - (val*val)/N ) / N;
        }
        System.out.println("Read MFCCs, now computing distances");
        /* Compute the unit distance matrix */
        double[][] dist = new double[numUnits][numUnits];
        for ( int i = 0; i < numUnits; i++ ) {
            dist[i][i] = 0.0; // <= Set the diagonal to 0.0
            for ( int j = 1; j < numUnits; j++ ) {
                /* Get the DTW distance between the two sequences: 
                System.out.println( "Entering DTW : "
                        + featDef.getFeatureName( 0 ) + " "
                        + featureVectors[i].getFeatureAsString( 0, featDef )
                        + ".length=" + melCep[i].length + " ; "
                        + featureVectors[j].getFeatureAsString( 0, featDef )
                        + ".length=" + melCep[j].length + " ." );
                System.out.flush(); */
                if (melCep[i].length == 0 || melCep[j].length == 0) {
                    if (melCep[i].length == melCep[j].length) { // both 0 length
                        dist[i][j] = dist[j][i] = 0;
                    } else {
                        dist[i][j] = dist[j][i] = 100000; // a large number
                    }
                } else {
                    dist[i][j] = dist[j][i] = dtwDist( melCep[i], melCep[j], sigma2 );
                    //System.out.println("Using Mahalanobis distance\n"+
                      //      			"Distance is "+dist[i][j]);
                }
            }
        }
        /* Write the matrix to disk */
        System.out.println( "Writing distance matrix to file [" + filename + "]");
        PrintWriter out = new PrintWriter(new BufferedOutputStream(new FileOutputStream(filename)));
        for ( int i = 0; i < numUnits; i++ ) {
            for ( int j = 0; j < numUnits; j++ ) {
                out.print( (float)(dist[i][j]) + " " );
            }
            out.print("\n");
        }
        out.flush();
        out.close();
        
    }
    
    
    /**
     * Computes an average Mahalanobis distance along the optimal DTW path
     * between two vector sequences.
     * 
     * The DTW constraint used here is:
     * D(i,j) = min {
     * D(i-2,j-1) + 2*d(i-1,j) + d(i,j) ;
     * D(i-1,j-1) + 2*d(i,j) ;
     * D(i-1,j-2) + 2*d(i,j-1) + d(i,j)
     * }
     * 
     * At the end of the DTW, the cumulated distance is normalized by the number
     * of local distances cumulated along the optimal path. Hence, the resulting
     * unit distance is homogeneous to an average having the order of magnitude
     * of a single Mahalanobis distance, and that for each pair of units.
     * 
     * @param seq1 The first sequence of (Mel-cepstrum) vectors.
     * @param seq2 The second sequence of (Mel-cepstrum) vectors.
     * @param sigma2 The variance of the vectors.
     * @return The average Mahalanobis distance along the optimal DTW path.
     */
    private double dtwDist( double[][] seq1, double[][] seq2, double[] sigma2 ) {
        
        if ( ( seq1.length <= 0 ) || ( seq2.length <= 0 ) ) {
            throw new RuntimeException( "Can't compute a DTW distance from a sequence with length 0 or negative. "
                    + "(seq1.length=" + seq1.length + "; seq2.length=" + seq2.length + ")" );
        }
        
        int l1 = seq1.length;
        int l2 = seq2.length;
        double[][] d = new double[l1][l2];
        double[][] D = new double[l1][l2];
        int[][] Nd = new int[l1][l2]; // <= Number of cumulated distances, for the final averaging
        double[] minV = new double[3];
        int[] minNd = new int[3];
        int minIdx = 0;
        /* Fill the local distance matrix */
        for ( int i = 0; i < l1; i++ ) {
            for ( int j = 0; j < l2; j++ ) {
                d[i][j] = mahalanobis( seq1[i],   seq2[j],   sigma2 );
            }
        }
        /* Compute the optimal DTW distance: */
        /* - 1st row/column: */
        /* (This part works for 1 frame or more in either sequence.) */
        D[0][0] = 2*d[0][0];
        for ( int i = 1; i < l1; i++ ) {
            D[i][0] = d[i][0];
            Nd[i][0] = 1;
        } 
        for ( int i = 1; i < l2; i++ ) {
                D[0][i] = d[0][i];
                Nd[0][i] = 1;
        }
        /* - 2nd row/column: */
        /* (This part works for 2 frames or more in either sequence.) */
        /* corner: i==1, j==1 */
        if ( (l1 > 1) && (l2 > 1) ) {
            minV[0] = 2*d[0][1] + d[1][1];  minNd[0] = 3;
            minV[1] = D[0][0] + 2*d[1][1];  minNd[1] = Nd[0][0] + 2;
            minV[2] = 2*d[1][0] + d[1][1];  minNd[2] = 3;
            minIdx = minV[0] < minV[1] ? 0 : 1;
            minIdx = minV[2] < minV[minIdx] ? 2 : minIdx;
            D[1][1] = minV[minIdx];
            Nd[1][1] = minNd[minIdx];

            /* 2nd row: j==1 ; 2nd col: i==1 */
            for ( int i = 2; i < l1; i++ ) {
                // Row: 
                minV[0] = D[i-2][0] + 2*d[i-1][1] + d[i][1];  minNd[0] = Nd[i-2][0] + 3;
                minV[1] = D[i-1][0] + 2*d[i][1];              minNd[1] = Nd[i-1][0] + 2;
                minV[2] = 2*d[i][0] + d[i][1];                minNd[2] = 3;
                minIdx = minV[0] < minV[1] ? 0 : 1;
                minIdx = minV[2] < minV[minIdx] ? 2 : minIdx;
                D[i][1] = minV[minIdx];
                Nd[i][1] = minNd[minIdx];
                }
            for ( int i = 2; i < l2; i++ ) {
                // Column: 
                minV[0] = 2*d[0][i] + d[1][i];                minNd[0] = 3;
                minV[1] = D[0][i-1] + 2*d[1][i];              minNd[1] = Nd[0][i-1] + 2;
                minV[2] = D[0][i-2] + 2*d[1][i-1] + d[1][i];  minNd[2] = Nd[0][i-2] + 3;
                minIdx = minV[0] < minV[1] ? 0 : 1;
                minIdx = minV[2] < minV[minIdx] ? 2 : minIdx;
                D[1][i] = minV[minIdx];
                Nd[1][i] = minNd[minIdx];
            }

        }
        /* - Rest of the matrix: */
        /* (This part works for 3 frames or more in either sequence.) */
        if ( (l1 > 2) && (l2 > 2) ) {
            for ( int i = 2; i < l1; i++ ) {
                for ( int j = 2; j < l2; j++ ) {
                    minV[0] = D[i-2][j-1] + 2*d[i-1][j] + d[i][j];  minNd[0] = Nd[i-2][j-1] + 3;
                    minV[1] = D[i-1][j-1] + 2*d[i][j];              minNd[1] = Nd[i-1][j-1] + 2;
                    minV[2] = D[i-1][j-2] + 2*d[i][j-1] + d[i][j];  minNd[0] = Nd[i-1][j-2] + 3;
                    minIdx = minV[0] < minV[1] ? 0 : 1;
                    minIdx = minV[2] < minV[minIdx] ? 2 : minIdx;
                    D[i][j] = minV[minIdx];
                    Nd[i][j] = minNd[minIdx];
                }
            }
        }
        /* Return */
        return( D[l1-1][l2-1] / (double)(Nd[l1-1][l2-1]) );
    }
    
    /**
     * Mahalanobis distance between two feature vectors.
     * 
     * @param v1 A feature vector.
     * @param v2 Another feature vector.
     * @param sigma2 The variance of the distribution of the considered feature vectors.
     * @return The mahalanobis distance between v1 and v2.
     */
    private double mahalanobis( double[] v1, double[] v2, double[] sigma2 ) {
        double sum = 0.0;
        double diff = 0.0;
        for ( int i = 0; i < v1.length; i++ ) {
            diff = v1[i] - v2[i];
            sum += ( (diff*diff) / sigma2[i] );
        }
        return( sum );
    }
    
    /**
     * Provide the progress of computation, in percent, or -1 if
     * that feature is not implemented.
     * @return -1 if not implemented, or an integer between 0 and 100.
     */
    public int getProgress()
    {
        return -1;
    }

}
