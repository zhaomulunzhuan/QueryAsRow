package com.chen;

import java.io.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

public class Block implements Serializable {
    private static final long serialVersionUID = 1L;
    private int numsBloomFilter;
    private List<Boolean> statusList;
    private List<BloomFilter> BloomFilterList;
    private int BlockIndex;
    private int max_numBloomFilter;
    private BitSet rowStorageBlock;
    private boolean  useRowStorage;//按行还是按列，这里的按列是指逻辑布隆过滤器存储连续，用于构建，添加，删除，按行是每个布隆过滤器的同一bit连续，用于查询
    private boolean serializeAsRow; // 指示序列化方式

    public Block(int BlockIndex,int numsBloomFilter){
        this.BlockIndex=BlockIndex;
        this.numsBloomFilter=numsBloomFilter;
        this.max_numBloomFilter = Integer.parseInt(ConfigReader.getProperty("Block-max-size"));
        this.statusList=new ArrayList<>(numsBloomFilter);
        this.useRowStorage=false;//初始构建时按列
        this.serializeAsRow=true;//按行序列化
        this.BloomFilterList=new ArrayList<>(numsBloomFilter);
        //初始化状态列表
        for (int i = 0; i < numsBloomFilter; i++) {
            statusList.add(false);
        }
        // 初始化 BloomFilterList
        int m= Integer.parseInt(ConfigReader.getProperty("BF-size"));
        int k= Integer.parseInt(ConfigReader.getProperty("k"));
        for (int i = 0; i < numsBloomFilter; i++) {
            BloomFilterList.add(new BloomFilter());
        }
        this.rowStorageBlock=new BitSet();
    }


    public void addElement(int bfIndex,String element){
        BloomFilterList.get(bfIndex).insertElement(element);
        statusList.set(bfIndex,true);
    }

    // 公共的 getter 方法来获取 numsBloomFilter 的值
    public int getNumsBloomFilter() {
        return numsBloomFilter;
    }

    public int getBlockIndex(){return BlockIndex;}

    public boolean getUseRowStorage(){return useRowStorage;}
    public List<BloomFilter> getBloomFilterList(){
        return BloomFilterList;
    }

    public BitSet getRowStorageBlock(){return rowStorageBlock;}

    public List<Boolean> getStatusList(){
        return statusList;
    }

//    public ArrayList<Boolean> queryExistence(String querykmer){//按列查询
//        //一个一个bf的查
//        ArrayList<Boolean> result = new ArrayList<>(Collections.nCopies(numsBloomFilter, false));
//        System.out.println(BloomFilterList);
////        System.out.println(rowStorageBlock);
//        for(int i=0;i<numsBloomFilter;i++){
//            BloomFilter bf= BloomFilterList.get(i);
//            if (bf.test(querykmer)){
//                result.set(i,true);
////                System.out.println(result);
//            }
////            bf.printBitSet();
//        }
//        return result;
//    }

    public BitSet queryKmer(List<Long> rowIdxs){//按行查询
        BitSet result = new BitSet(numsBloomFilter);
        result.set(0, numsBloomFilter);//所有位设置为true
        for(long rowIndex:rowIdxs){
            int startIndex= (int) (rowIndex*numsBloomFilter);
            int endIndex=startIndex+numsBloomFilter;
            endIndex = Math.min(endIndex, rowStorageBlock.length());

            BitSet rangeBits = rowStorageBlock.get(startIndex, endIndex);

            result.and(rangeBits);

        }
        return result;
    }

    // 添加方法来判断 Block 是否为空
    public boolean isEmpty() {
        //// 检查 numsBloomFilter 是否等于 0 或者 statusList 中的所有元素是否都为 false
        return numsBloomFilter == 0 || statusList.stream().allMatch(b -> !b);
    }

    public boolean isFull() {
        // 判断 numsBloomFilter 是否等于 64，并且 statusList 中的所有元素是否都为 true
        return numsBloomFilter == max_numBloomFilter && statusList.stream().allMatch(Boolean::booleanValue);
    }


    public void addBF(){//插入
        if(numsBloomFilter<max_numBloomFilter){
            // 新建一个布隆过滤器，并将其状态置为 false
            BloomFilterList.add(new BloomFilter());
            statusList.add(false);
            // 增加布隆过滤器数量
            numsBloomFilter++;
        }else {
            System.err.println(BlockIndex+"号索引块的布隆过滤器数量已达上限，不可添加");
        }

    }
    //寻找一个插入位置，可以是删除留下的空位置，或者添加新的布隆过滤器
    public Object[] findInsertposition() {//插入
        Object[] result = new Object[2];
        // 遍历状态列表
        for (int i = 0; i < statusList.size(); i++) {
            // 如果找到一个状态为 false 的布隆过滤器
            if (!statusList.get(i)) {
                result[0] = i; // 设置位置索引
                result[1] = false; // 设置标识为 false，表示没有添加新的布隆过滤器
                return result;
            }
        }

        // 如果状态列表中没有 false 状态的布隆过滤器，并且当前布隆过滤器数量没有达到最大值
        if (numsBloomFilter < max_numBloomFilter) {//需要添加新的布隆过滤器
            // 返回新建布隆过滤器的索引和标识为 true，表示已经添加了新的布隆过滤器
            result[0] = statusList.size();
            result[1] = true;
            return result;
        }

        // 如果布隆过滤器数量已经达到最大值，则返回 null 表示无法插入
        return null;
    }

    public void convertTORowStorage(){
        if(!useRowStorage){//当前块是按列存储
            int m= Integer.parseInt(ConfigReader.getProperty("BF-size"));
            int block_max_size= Integer.parseInt(ConfigReader.getProperty("Block-max-size"));
            rowStorageBlock=new BitSet(m*numsBloomFilter);
            for(int bfIndex=0;bfIndex<numsBloomFilter;bfIndex++){
                BloomFilter bf=BloomFilterList.get(bfIndex);
                for(int bitIndex=0;bitIndex<m;bitIndex++){
                    boolean bit=bf.getBitarray().get(bitIndex);
                    rowStorageBlock.set(bitIndex*numsBloomFilter+bfIndex,bit);
                }
            }
            BloomFilterList=null;
            useRowStorage=true;
        }

    }

    public void convertToColumnStorage(){
        if (useRowStorage) {
            int m= Integer.parseInt(ConfigReader.getProperty("BF-size"));
            int block_max_size= Integer.parseInt(ConfigReader.getProperty("Block-max-size"));
            // 根据 rowStorageBlock 中的数据重新构建 BloomFilterList
            BloomFilterList = new ArrayList<>(numsBloomFilter);
            for (int bfIndex = 0; bfIndex < numsBloomFilter; bfIndex++) {
                BloomFilter bf = new BloomFilter();
                // 将 rowStorageBlock 中的数据转移到 BloomFilter 中
                for (int bitIndex = 0; bitIndex < m; bitIndex++) {
                    boolean bit = rowStorageBlock.get(bitIndex * numsBloomFilter + bfIndex);
                    bf.getBitarray().set(bitIndex, bit);
                }
                BloomFilterList.add(bf);
            }
            // 释放 rowStorageBlock 占用的内存
            rowStorageBlock = null;
            useRowStorage = false; // 更新存储方式标志
        }
    }

    // 序列化方法
    public void serialize() {
        if (serializeAsRow && !useRowStorage) {
            // 如果按行序列化，并且当前是按列存储，则先进行转换为按行存储
//            System.out.println("当前BloomFilterList大小"+);
            convertTORowStorage();
//            System.out.println("rowStorageBlock的大小");
        } else if (!serializeAsRow && useRowStorage) {
            convertToColumnStorage();
        }
    }


    // 输出函数，用于查看 Block 的信息
    public void printBlockInfo() {
        System.out.println("Block Index: " + BlockIndex);
        System.out.println("Number of Bloom Filters: " + numsBloomFilter);
        if (useRowStorage){
            System.out.println("按行存储");
        }else {
            System.out.println("按列存储");
            for (int i = 0; i < numsBloomFilter; i++) {
                System.out.println("Bloom Filter " + i + ": " + (statusList.get(i) ? "Active" : "Inactive"));
                System.out.println("Bloom Filter " + i +"中1的数量"+BloomFilterList.get(i).countOnes());
            }
        }
    }

}
